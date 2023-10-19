import cats.effect.std.Queue
import cats.effect.{ExitCode, IO, IOApp, Ref}
import com.comcast.ip4s.IpLiteralSyntax
import fs2.{Pipe, Stream}
import fs2.concurrent.Topic
import io.circe.Decoder.Result
import io.circe.{Decoder, DecodingFailure, HCursor}
import org.http4s.circe.jsonOf
import org.http4s.dsl.Http4sDsl
import org.http4s.{EntityDecoder, HttpApp, HttpRoutes}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import play.api.libs.json.Format.GenericFormat
import play.api.libs.json.{Format, JsArray, JsBoolean, JsError, JsNull, JsNumber, JsObject, JsResult, JsString, JsSuccess, JsValue, Json, Reads}

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}

object Main extends IOApp.Simple {

  /** Live support chat app
    *
    * Functionality:
    *   - any user can join the chat app and talk with the support
    *   - input your name
    *   - get queued
    *   - support notices you
    *   - joins your chat
    *   - you talk shit with him/her
    */

  final case class ChatMetaData(chatId: String, supportId: Option[String])

  type CustomerId          = String
  type CachedChatMetaData  = Map[CustomerId, ChatMetaData]
  type CachedConversations = Map[CustomerId, Conversation]

  type TopicsCache = Map[CustomerId, Topic[IO, Message]]

  sealed trait Message

  sealed trait In  extends Message
  sealed trait Out extends Message

  sealed trait FromCustomer extends In
  sealed trait FromSupport  extends In

  sealed trait ToCustomer extends Out
  sealed trait ToSupport  extends Out

  sealed trait ChatMessage extends In with Out

  final case class MessageFromCustomer(
    customerId: String,
    supportId: String,
    content: String,
  ) extends ChatMessage
      with FromCustomer
      with ToSupport

  final case class MessageFromSupport(
    supportId: String,
    customerId: String,
    content: String,
  ) extends ChatMessage
      with FromSupport
      with ToCustomer

  final case class Conversation(messages: Vector[ChatMessage]) extends ToCustomer with ToSupport {
    def add(msg: ChatMessage): Conversation =
      copy(messages :+ msg)
  }

  object Conversation {
    def empty: Conversation = Conversation(Vector.empty)
  }

  final case class Connect(username: String) extends FromCustomer
  final case class Disconnect(id: String)    extends FromCustomer

  final case class SupportJoin(
    username: String,
    customerId: String,
    chatId: String,
    supportId: Option[String],
  ) extends In

  final case class CustomerJoin(
    username: String,
    customerId: String,
    supportId: Option[String],
    chatId: String,
  ) extends In

  final case class Connected(
    customerId: String,
    username: String,
  ) extends ToCustomer

  final case class SupportJoined(
    supportId: String,
    supportUsername: String,
    customerId: String,
    chatId: String,
  ) extends ToSupport

  final case class CustomerJoined(
    customerId: String,
    chatId: String,
  ) extends ToSupport

  implicit val chatMsg: Format[ChatMessage] = new Format[ChatMessage] {
    override def reads(json: JsValue): JsResult[ChatMessage] =
      msgFromCustomerFmt.reads(json) orElse
        msgFromSupportFmt.reads(json)

    override def writes(o: ChatMessage): JsValue = o match {
      case mfc: MessageFromCustomer => msgFromCustomerFmt.writes(mfc)
      case mfs: MessageFromSupport  => msgFromSupportFmt.writes(mfs)
    }
  }

  implicit val msgFromCustomerFmt: Format[MessageFromCustomer] = Json.format[MessageFromCustomer]
  implicit val msgFromSupportFmt: Format[MessageFromSupport]   = Json.format[MessageFromSupport]

  implicit val conversationFmt: Format[Conversation] = Json.format[Conversation]

  implicit val connectFmt: Format[Connect]           = Json.format[Connect]
  implicit val supportJoinFmt: Format[SupportJoin]   = Json.format[SupportJoin]
  implicit val customerJoinFmt: Format[CustomerJoin] = Json.format[CustomerJoin]
  implicit val disconnectFmt: Format[Disconnect]     = Json.format[Disconnect]

  implicit val connectedFmt: Format[Connected]           = Json.format[Connected]
  implicit val supportJoinedFmt: Format[SupportJoined]   = Json.format[SupportJoined]
  implicit val customerJoinedFmt: Format[CustomerJoined] = Json.format[CustomerJoined]

  final case class RequestBody(`type`: String, args: In)

  implicit val RequestBodyFormat: Reads[RequestBody] = new Reads[RequestBody] {
    override def reads(json: JsValue): JsResult[RequestBody] =
      (json \ "type").validate[String] flatMap {
        case m @ "MessageFromCustomer" =>
          msgFromCustomerFmt
            .reads(json("args"))
            .map(RequestBody(m, _))
        case s @ "MessageFromSupport"  =>
          msgFromSupportFmt
            .reads(json("args"))
            .map(RequestBody(s, _))
        case c @ "Connect"             =>
          connectFmt
            .reads(json("args"))
            .map(RequestBody(c, _))
        case d @ "Disconnect"          =>
          disconnectFmt
            .reads(json("args"))
            .map(RequestBody(d, _))
        case j @ "SupportJoin"         =>
          supportJoinFmt
            .reads(json("args"))
            .map(RequestBody(j, _))
        case c @ "CustomerJoin"        =>
          customerJoinFmt
            .reads(json("args"))
            .map(RequestBody(c, _))
      }
  }

  type ChatId = String

  val run = for {
    // topics
    // customer id -> topic
    topic                    <- Topic[IO, Message]
    topics                   <- IO.ref(Map("1" -> topic))
    q                        <- Queue.unbounded[IO, Message]
    // chats
    // chat id -> customer id
    customerIdToChatId       <- IO.ref(Map.empty[CustomerId, ChatId])
    customerIdToConversation <- IO.ref(Map.empty[CustomerId, Conversation])
    _                        <- EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"9000")
      .withHttpWebSocketApp(httpApp(_, topics, q, customerIdToChatId, customerIdToConversation, topic))
      .withIdleTimeout(120.seconds)
      .build
      .useForever
  } yield ExitCode.Success

  private def httpApp(
    wsb: WebSocketBuilder2[IO],
    topics: Ref[IO, TopicsCache],
    q: Queue[IO, Message],
    customerIdToChatId: Ref[IO, Map[CustomerId, ChatId]],
    customerIdToConversation: Ref[IO, Map[CustomerId, Conversation]],
    globalTopic: Topic[IO, Message],
  ): HttpApp[IO] = {
    val dsl = new Http4sDsl[IO] {}
    import dsl._
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "join" =>
        for {
          connect  <- req
            .as[String]
            .map(Json.parse(_).as[RequestBody].args.asInstanceOf[Connect])
            .flatTap(IO.println)
          result   <- IO.delay {
            val chatId     = java.util.UUID.randomUUID().toString.replaceAll("-", "")
            val customerId = java.util.UUID.randomUUID().toString.replaceAll("-", "")

            (connect, customerId, chatId)
          }
          _        <- customerIdToChatId.getAndUpdate(_.updated(result._2, result._3))
          _        <- IO.println("ChatsRef:")
          _        <- customerIdToChatId.get.flatTap(IO.println)
          topic    <- Topic[IO, Message]
          _        <- topics.getAndUpdate(_.updated(result._3, topic))
          _        <- IO.println("TopicsRef:")
          _        <- topics.get.flatTap(IO.println)
          response <- Ok {
            s"""{
              |  "username": "${result._1.username}",
              |  "customerId": "${result._2}",
              |  "chatId": "${result._3}"
              |}""".stripMargin
          }
        } yield response

      case chatReq @ GET -> Root / "chat" =>
//          implicit val d: Decoder[CustomerJoin] = new Decoder[CustomerJoin] {
//            override def apply(c: HCursor): Result[CustomerJoin] =
//              for {
//                chatId <- c.get[String]("chatId")
//                customerId <- c.get[String]("customerId")
//                username <- c.get[String]("username")
//              } yield CustomerJoin(username, customerId, None, chatId)
//          }

//          implicit val RequestBodyDecoder: Decoder[RequestBody] = new Decoder[RequestBody] {
//            final def apply(c: HCursor): Decoder.Result[RequestBody] = {
//              for {
//                messageType <- c.get[String]("type")
//                args <- messageType match {
//                  case "CustomerJoin" => c.get[CustomerJoin]("args")
//                  case _ => Left(DecodingFailure("Invalid message type", c.history))
//                }
//              } yield RequestBody(messageType, args)
//            }
//          }

//          implicit val RequestBodyEntityDecoder: EntityDecoder[IO, RequestBody] = jsonOf[IO, RequestBody]

        val findTopicById: String => IO[Topic[IO, Message]] = chatId =>
          topics.get.flatMap {
            _.get(chatId)
              .map(IO.pure)
              .getOrElse(IO.raiseError(new RuntimeException(s"Chat with $chatId does not exist")))
          }

        val rec: Pipe[IO, WebSocketFrame, Unit] = _.evalMap {
            case text: WebSocketFrame.Text =>
              println("line 252")
              println(text.str)
              val body = Try(Json.parse(text.str.trim).as[RequestBody])
              body match {
                case Failure(exception) => IO.unit
                case Success(value) =>
                  value.args match {
                    case CustomerJoin(username, customerId, supportId, chatId) =>
                      val d = for {
                        _ <- IO.println("resp")
                        topic <- findTopicById("1")
                        _ <- IO.println("respaaaa")
                        _ <- topic.publish1(CustomerJoin(username, customerId, supportId, chatId))
                      } yield ()

                      d
                  }
              }
          }

        val receive: Pipe[IO, WebSocketFrame, Unit] = _.collect {
          case text: WebSocketFrame.Text =>
            println("line 252")
            println(text.str)
            val body = Try(Json.parse(text.str.trim).as[RequestBody])
            body match {
              case Failure(exception) => ()
              case Success(value) =>
                value.args match {
                  case CustomerJoin(username, customerId, supportId, chatId) =>
                    for {
                      _ <- IO.println("resp")
                      topic <- findTopicById(customerId)
                    } yield topic.publish.compose[Stream[IO, WebSocketFrame]](_.collect {
                      case WebSocketFrame.Text(str, bool) =>
                        CustomerJoin(username, customerId, supportId, chatId)
                    })
                }
            }
        }

//          val lazyReceive: IO[Pipe[IO, WebSocketFrame, Unit]] = for {
//              _    <- IO.println("here???????")
//              chatId <- chatReq.as[RequestBody].onError(IO.println)
//              _    <- IO.println(chatId)
////              chatId <- IO.pure(body.args match {
////                case SupportJoin(_, _, chatId, _) => chatId
////                case CustomerJoin(_, _, _, chatId) => chatId
////              })
//              _ <- IO.println("for real dude?")
//              topic <- findTopic(chatId.args.asInstanceOf[CustomerJoin].chatId)
//              _     <- IO.println(s"topic: $topic")
//            } yield {
//             val str: Pipe[IO, WebSocketFrame, Unit] = _.collect {
//               case text: WebSocketFrame.Text =>
//                 println("here")
//                 val body = Json.parse(text.str.trim).as[RequestBody]
//                 println(body)
//                 body.args match {
//                   case customer: FromCustomer => ???
//                   case support: FromSupport => ???
//                   case message: ChatMessage => ???
//                   case SupportJoin(username, customerId, chatId, supportId) => ???
//                   // first join customer
//                   case CustomerJoin(username, customerId, None, chatId) => {
//                     val d: Stream[IO, Message] => Stream[IO, Unit] = topic.publish.compose[Stream[IO, Main.Message]] { _.map(identity) }
//                     for {
//                       _ <- IO.println("...")
//                       a <- IO(d)
//                     } yield a
//
//                   }
//                 }
//             }
//
//                 str
//          }

//          val receive: Pipe[IO, WebSocketFrame, Unit] = (input: Stream[IO, WebSocketFrame]) =>
//            Stream.eval(lazyReceive).flatMap(pipe => pipe(input))

        val send: Stream[IO, WebSocketFrame.Text] = Stream
          .eval {
            for {
              _     <- IO.println("xd")
              body  <- chatReq.as[String]
              _     <- IO.println(body)
              topic <- findTopicById("1")
              _     <- IO.println("here? yes")
            } yield topic
              .subscribe(10)
              .collect { case in: In =>
                in match {
                  case SupportJoin(username, customerId, chatId, supportId)  =>
                    WebSocketFrame.Text("boom 1")
                  case CustomerJoin(username, customerId, supportId, chatId) =>
                    WebSocketFrame.Text("boom 2")
                }
              }
          }
          .flatMap(identity)

        val correctSend: IO[Stream[IO, WebSocketFrame]] =
          for {
            _      <- IO.println("do picia")
            _      <- chatReq.as[String].flatTap(IO.println)
            body   <- chatReq.as[String].map(Json.parse(_).as[RequestBody]).onError(IO.println)
            _      <- IO.println("298")
            chatId <- IO.pure {
              body.args match {
                case SupportJoin(username, customerId, chatId, supportId)  => chatId
                case CustomerJoin(username, customerId, supportId, chatId) => chatId
              }
            }
            topic  <- findTopicById(chatId)
            stream <- IO {
              topic.subscribe(10).collect { case in: In =>
                in match {
                  case SupportJoin(username, customerId, chatId, supportId)  =>
                    WebSocketFrame.Text("erti")
                  case CustomerJoin(username, customerId, supportId, chatId) =>
                    WebSocketFrame.Text("erti")
                }
              }
            }

          } yield stream

        val da: Stream[IO, WebSocketFrame] = Stream.eval(correctSend).flatten

        val str = Stream.awakeEvery[IO](5.seconds).as(WebSocketFrame.Text("response"))


        wsb.build(send, rec)
    }
  }.orNotFound
}
