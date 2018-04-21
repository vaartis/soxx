package soxx.mongowrapper

import javax.inject._

import org.mongodb.scala.MongoClient

@Singleton
class Mongo() {
  val Client: MongoClient = MongoClient()
  lazy val DB = Client.getDatabase("soxx")

  implicit def wrapper2mongo(arg: Mongo): MongoClient = arg.Client
}
