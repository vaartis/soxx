# Soxx!

This is supposed to be a thing to scrape and index imageboards and
boorus. It supports a number of them now and adding support is pretty trivial.

This project is done in Scala+Play+Akka, because I like actors and erlang :P

Please do suggest things and feel free to fork and contribute. Documentation
is a little lacking at the moment, but i will try to make it better in the future.

Support for amazon s3 compaitable storage is also planned, right now images
are just indexed and not downloaded, i.e. they are linked from the page
we got them from (sorry).

I will probably also set up some testing and that stuff where appropriate.

The only outside dependencies are NodeJS and MongoDB, just installing them
should be enough to get things running by just typing `sbt run`.

## Notes

In order for collection watching to work mongodb needs to be configured as a cluster.
