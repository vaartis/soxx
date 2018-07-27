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

  metadataOnly: Boolean = true,
  s3: Boolean = false,
  s3url: Option[String] = None,

  indexedOn: Date = new Date(),
  _id: ObjectId = new ObjectId() // Hack to make it deserialize
) {
  def toFrontend(host: String): FrontendImage = {
    import io.scalaland.chimney.dsl._

    this.into[FrontendImage]
      .withFieldConst(
        _.image,
        {
          if (metadataOnly) {
            from.head.image
          } else { if (s3) { s3url.get } else { f"$host/image_files/${md5}${extension}" } }
        }
      )
      .transform
  }
}

object Image {
  /** Get all fields of integer type.
    *
    * This is used to allow or disallow using gt/lt operators when searching by
    * field value in [[controllers.apiv1.APIV1Controller.tagStringToQuery]], so that one
    * could only compare numerical fields.
   */
  lazy val intFields = {
    import scala.reflect.runtime.universe._

    typeOf[Image].members
      .collect { case m: MethodSymbol if m.isGetter && m.returnType <:< typeOf[Int] => m.name.toString }
      .toSeq
  }
}

case class FrontendImage(
  height: Int,
  width: Int,
  tags: Seq[String],
  md5: String,
  from: Seq[From],
  extension: String,

  indexedOn: Date,
  _id: ObjectId,

  image: String
)

object FrontendImage {
  // Hacks here. Serialization doesn't work on these things out of the box.
  // Date has to be overrided because of the MongoDB date formatting

  implicit val oidFormat = new Format[ObjectId] {
    override def reads(json: JsValue) = JsSuccess(new ObjectId(json.as[String]))
    override def writes(o: ObjectId) = JsString(o.toString())
  }

  implicit val implDateWrites = dateWrites("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

  implicit val imageFormat = Json.format[FrontendImage]
}
