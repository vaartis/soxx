package soxx.mongowrapper

import javax.inject._

import org.mongodb.scala.MongoClient
import org.mongodb.scala.bson.codecs.Macros._
import org.mongodb.scala.bson.codecs.DEFAULT_CODEC_REGISTRY
import org.bson.codecs.configuration.CodecRegistries.{fromRegistries, fromProviders}

import play.api.inject.ApplicationLifecycle
import scala.concurrent.{ ExecutionContext, Future }

import soxx.scrappers.Image

@Singleton
class Mongo @Inject() (implicit lifecycle: ApplicationLifecycle, ec: ExecutionContext) {
  val client: MongoClient = MongoClient()

  val codecRegistry = fromRegistries(fromProviders(classOf[Image]), DEFAULT_CODEC_REGISTRY)

  lazy val db = client.getDatabase("soxx").withCodecRegistry(codecRegistry)

  lifecycle.addStopHook { () =>
    Future { client.close() }
  }
}
