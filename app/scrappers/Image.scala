package soxx.scrappers

import org.bson.types.ObjectId
import org.mongodb.scala.bson.collection.immutable.Document
import play.api.libs.json._

case class Image(
  height: Int,
  width: Int,
  tags: Seq[String],
  md5: String,
  from: Seq[From],
  extension: String,

  metadataOnly: Boolean,

  indexedOn: java.util.Date = new java.util.Date(),
  _id: ObjectId = new ObjectId() // Hack to make it deserialize
)

object Image {
  // Hack!
  implicit val oidFormat = new Format[ObjectId] {
    override def reads(json: JsValue) = JsSuccess(new ObjectId(json.as[String]))
    override def writes(o: ObjectId) = JsString(o.toString())
  }

  implicit val imageFormat = Json.format[Image]
}
