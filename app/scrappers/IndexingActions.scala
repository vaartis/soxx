package soxx.scrappers

abstract class IndexingAction
case class StartIndexing(fromPage: Int = 1) extends IndexingAction
case object StopIndexing extends IndexingAction
