package soxx.scrappers

import play.api.libs.json._

case class BoardInfo(_id: String, favicon: String)

object BoardInfo {
  implicit val boardInfoFormat = Json.format[BoardInfo]
}
