# Soxx!

[![Build Status](https://travis-ci.org/vaartis/soxx.svg?branch=master)](https://travis-ci.org/vaartis/soxx)
[![Coverage Status](https://coveralls.io/repos/github/vaartis/soxx/badge.svg)](https://coveralls.io/github/vaartis/soxx)

As of now, we can index and scrape images from several popular imageboards
(as much as their API allows it) via a basic web interface admin panel. Adding
new imageboards with APIs ideantical to other popular ones is simple with the `scrappers.toml`
configuration file.

The image viewing part is somewhat better: you can view images either
right on the search page or their own page, you can search images by
excluding, including tags and regular expressions run on tags.

Please do suggest things and feel free to fork and contribute. Documentation
is a little lacking at the moment, but i will try to make it better in the future.

There's support for uploading images to S3-compaitable services, it is optional and is disabled by default.
As of now, images are indexed and you can then download them because indexing is much faster.

## Setting up

There are not many dependencies, you'll need at least the following:
* Scala 2.12 & sbt
* MongoDB (preferably 3.6+)
* NodeJS & npm (to compile frontend stuff)

First, you'll need to set up a MongoDB cluseter (it doesn't need to be
several machines, it's just that collection watching is available only for
clusters). To do that you'll have to find your MongoDB config file (probably `/etc/mongodb.conf`) and
uncomment (or add) these lines:
```
replication:
  replSetName: your cluster name, it's not really important, you can just use something like "soxx0"
```
You can also set some other settings for MongoDB there, see [documentation](https://docs.mongodb.com/manual/reference/configuration-options/).

After modifying your config file, launch the `mongo` console and write `rs.initiate()` there, that will actually make it a cluster.

After cloning the repository, you'll need to `cd` into the assets directory and
run `npm install` there, to install all frontend dependencies.

When everything it ready, you can `cd` back into the project directory and start `sbt` there. `run` will
launch the project in the development mode, while `runProd` will start it in the production mode (not really ready
yet..).

### Additional configuration

The configuration file you'll need to edit is `conf/application.conf`.
The configuration is written in the [HOCON](https://github.com/lightbend/config) format.
It's pretty self-explanatory and it it's not, there is probably a documentation string there.
Some important things include:
* `soxx.mongo.connectionString` and `dbName` is  where your mongodb lives + username and password
  (see more [here](https://docs.mongodb.com/manual/reference/connection-string/))
  and mongodb database name, since one instance can have more than one database
* `soxx.s3` is responsible for S3-compaitable service integration. It is disabled by default,
  but if you enable it with `soxx.s3.enabled = true`, you'll need to configure other options
  present in the config file (you can get all of them from your S3 console or web UI). When S3 is enabled,
  images will be downloaded to it, rather than to the local machine (although the machine will still
  work as a pipe from the remote image storage to your S3 service, no files will be saved)
* `soxx.scrappers.configFile` is the TOML configuration file where you can define new scrappers using
  some existing patterns, you can read more about it in the file itself (`scrappers.toml` by default)
* `soxx.scrappers.downloadDirectory` is the directory to which images will be saved. You should change
  it to some absolute path on an external drive with lots of space. It defaults to the `images` directory
  relative to the current working directory though.
* `play.http.secret.key` is the key, with which client information will be encrypted, you can read more
  about it [here](https://www.playframework.com/documentation/latest/ProductionConfiguration).

## Somewhat of a roadmap

- [x] S3 integration
- Searching
  - [x] Basic image search by tags and tag regular expressions
  - [ ] Advanced image search with grouping, AND, OR
  - [ ] Similar image search
  - [ ] Searching images by their properties (width, height, rating)

## Contributions

All contributions in any JVM language are welcome! This project may
not be scala-only if there are people interested in it but they also
don't want to learn scala (as it is pretty complex!).

## Screenshots

That's how the image embded in the search page looks right now (all pictures
belong to their respective authors)

![Embedded image](screenshots/embedded.png)
