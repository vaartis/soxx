package soxx.mongowrapper

import javax.inject._

import org.mongodb.scala.MongoClient
import play.api.inject.ApplicationLifecycle
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class Mongo @Inject() (implicit lifecycle: ApplicationLifecycle, ec: ExecutionContext) {
  val client: MongoClient = MongoClient()
  lazy val db = client.getDatabase("soxx")

  lifecycle.addStopHook { () =>
    client.close()

    Future { }
  }
}
