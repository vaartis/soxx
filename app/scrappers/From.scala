package soxx.scrappers

import play.api.libs.json._

case class From(
  id: Int,
  name: String,
  imageName: String,
  score: Int,
  post: String,
  image: String,
  thumbnail: String
)

object From {
  implicit val fromFormat = Json.format[From]
}
