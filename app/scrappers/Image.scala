package soxx.scrappers

import java.util.Date

import org.bson.types.ObjectId
import play.api.libs.json._
import play.api.libs.json.Writes.dateWrites

case class Image(
  height: Int,
  width: Int,
  tags: Seq[String],
  md5: String,
  from: Seq[From],
  extension: String,

  metadataOnly: Boolean,

  indexedOn: Date = new Date(),
  _id: ObjectId = new ObjectId() // Hack to make it deserialize
)

object Image {
  // Hacks here. Serialization doesn't work on these things out of the box.
  // Date has to be overrided because of the MongoDB date formatting

  implicit val oidFormat = new Format[ObjectId] {
    override def reads(json: JsValue) = JsSuccess(new ObjectId(json.as[String]))
    override def writes(o: ObjectId) = JsString(o.toString())
  }

  implicit val implDateWrites = dateWrites("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

  implicit val imageFormat = Json.format[Image]
}
