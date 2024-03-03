package ws

import ws.Message.Out
import play.api.libs.json._

// WebSocket message sent from Server to Client
case class ServerWsMsg(args: Out) extends AnyVal {
  def `type`: String = args.getClass.getSimpleName
}

object ServerWsMsg {
  import Message.Out.codecs.wo

  implicit val ww: Writes[ServerWsMsg] =
    (msg: ServerWsMsg) =>
      JsObject(
        Map(
          "type" -> JsString(msg.`type`),
          "args" -> wo.writes(msg.args),
        ),
      )
}
