package soxx.s3

import javax.inject._
import java.io.{ File, InputStream }
import java.nio.file.Paths
import scala.util.control.NonFatal

import play.api.{ Logger, Configuration }
import io.minio.{ MinioClient, ErrorCode }
import io.minio.errors.ErrorResponseException
import akka.actor._

case class UploadImage(name: String, inputStream: InputStream, size: Long, contentType: String)
case class ImageExists(name: String)

@Singleton
class S3Uploader @Inject() (config: Configuration) extends Actor {
  val logger = Logger(this.getClass)

  val (endpoint, accessKey, secretKey, bucketName, region) = (
    config.get[String]("soxx.s3.endpoint"),
    config.get[String]("soxx.s3.access-key"),
    config.get[String]("soxx.s3.secret-key"),
    config.get[String]("soxx.s3.bucket-name"),
    config.getOptional[String]("soxx.s3.region")
  )

  lazy val client = if (region.isEmpty)
    new MinioClient(endpoint, accessKey, secretKey)
  else
    new MinioClient(endpoint, accessKey, secretKey, region.get)

  override def preStart {
    // Ensure the bucket exists
    if (!client.bucketExists(bucketName)) {
      client.makeBucket(bucketName)
    }
  }

  override def receive = {
    case UploadImage(name, inputStream, size, contentType) =>
      logger.info(f"Request to upload an image $name")

      try {
        client.putObject(bucketName, name, inputStream, size, contentType)
        logger.info(f"Uploaded image $name")
        sender ! true
      } catch {
        case NonFatal(e) =>
          logger.error(s"Error uploading image to S3: $e")
          sender ! false
      }

    case ImageExists(name) =>
      try {
        val _ = client.statObject(bucketName, name)

        sender ! true
      } catch {
        case e: ErrorResponseException if e.errorResponse.errorCode == ErrorCode.NO_SUCH_KEY =>
          sender ! false
      }
  }
}
