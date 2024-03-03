package redis

import domain.User
import play.api.libs.json._

case class PubSubMessage(`type`: String, args: PubSubMessage.Args)

object PubSubMessage {

  def from(args: PubSubMessage.Args): PubSubMessage =
    PubSubMessage(`type` = args.`type`, args = args)

  sealed trait Args {
    def `type`: String
  }

  object Args {

    /** Sent when user joins the chat system
      */
    case class UserJoined(user: domain.User) extends Args {
      override def `type`: String = "UserJoined"
    }

    /** Sent when user leaves the chat system
      */
    case class UserLeft(chatId: String) extends Args {
      override def `type`: String = "UserLeft"
    }

    /** Sent when Support specialist joins the user's chat
      */
    case class SupportJoined(support: domain.Support) extends Args {
      override def `type`: String = "SupportJoined"
    }

    object codecs {
      implicit val wuj: Writes[UserJoined] = Json.writes[domain.User].contramap(_.user)
      implicit val wul: Writes[UserLeft] = ul => play.api.libs.json.Writes.StringWrites.writes(ul.chatId)
      implicit val wsj: Writes[SupportJoined] = Json.writes[domain.Support].contramap(_.support)
      implicit val wa: Writes[Args] = {
        case uj: Args.UserJoined    => wuj.writes(uj)
        case ul: Args.UserLeft      => wul.writes(ul)
        case uj: Args.SupportJoined => wsj.writes(uj)
      }
    }
  }

  import Args.codecs._

  implicit val wpsm: Writes[PubSubMessage] = (psm: PubSubMessage) =>
    JsObject(
      Map(
        "type" -> JsString(psm.`type`),
        "args" -> implicitly[Writes[Args]].writes(psm.args),
      ),
    )
}
