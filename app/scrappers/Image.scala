package soxx.scrappers

import play.api.libs.json._

case class Image(
  originalID: Int,
  height: Int,
  width: Int,
  score: Int,
  name: String,
  tags: Seq[String],
  md5: String,
  from: String,
  extension: String,

  originalPost: String,
  originalImage: String,
  originalThumbnail: String,

  metadataOnly: Boolean,

  indexedOn: java.util.Date = new java.util.Date()
)

object Image {
  implicit val imageFormat = Json.format[Image]
}
