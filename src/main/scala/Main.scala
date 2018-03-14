object Main {
    def main(args: Array[String]) {
        // make backend implicits for asynchrony
        import fs2.internal.NonFatal
        import java.nio.channels.AsynchronousChannelGroup
        import scala.concurrent.ExecutionContext
        // TODO: Unfuckulate this giant mess
        object backendImplicits {
            import fs2._
            import java.util.concurrent.Executors
            implicit val tcpACG : AsynchronousChannelGroup = namedACG.namedACG("tcp")
            //                                                                                                        hehe
            implicit val Sch : Scheduler = Scheduler.fromScheduledExecutorService(
                Executors.newScheduledThreadPool(
                    16, threadFactoryFactoryProxyBeanFactory.mkThreadFactory("scheduler", daemon = true)
                )
            )
        }
        object namedACG {
            /**
            Lifted verbatim from fs2 tests.
            I have no idea what it does, but it makes stuff work...
            */
            import java.nio.channels.AsynchronousChannelGroup
            import java.lang.Thread.UncaughtExceptionHandler
            import java.nio.channels.spi.AsynchronousChannelProvider
            import java.util.concurrent.ThreadFactory
            import java.util.concurrent.atomic.AtomicInteger
            def namedACG(name:String):AsynchronousChannelGroup = {
                val idx = new AtomicInteger(0)
                AsynchronousChannelProvider.provider().openAsynchronousChannelGroup(
                    16
                    , new ThreadFactory {
                        def newThread(r: Runnable): Thread = {
                            val t = new Thread(r, s"fs2-AG-$name-${idx.incrementAndGet() }")
                            t.setDaemon(true)
                            t.setUncaughtExceptionHandler(
                                new UncaughtExceptionHandler {
                                    def uncaughtException(t: Thread, e: Throwable): Unit = {
                                        println("----------- UNHANDLED EXCEPTION ---------")
                                        e.printStackTrace()
                                    }
                                }
                            )
                            t
                        }
                    }
                )
            }
        }
        object threadFactoryFactoryProxyBeanFactory {
          import java.lang.Thread.UncaughtExceptionHandler
          import java.util.concurrent.{Executors, ThreadFactory}
          import java.util.concurrent.atomic.AtomicInteger
          def mkThreadFactory(name: String, daemon: Boolean, exitJvmOnFatalError: Boolean = true): ThreadFactory = {
            new ThreadFactory {
              val idx = new AtomicInteger(0)
              val defaultFactory = Executors.defaultThreadFactory()
              def newThread(r: Runnable): Thread = {
                val t = defaultFactory.newThread(r)
                t.setName(s"$name-${idx.incrementAndGet()}")
                t.setDaemon(daemon)
                t.setUncaughtExceptionHandler(new UncaughtExceptionHandler {
                    def uncaughtException(t: Thread, e: Throwable): Unit = {
                        ExecutionContext.defaultReporter(e)
                        if (exitJvmOnFatalError) {
                            e match {
                                case NonFatal(_) => ()
                                case fatal => System.exit(-1)
                            }
                        }
                    }
                }
                                             )
                t
              }
            }
          }
        }
        import backendImplicits._
        import cats.effect._
        import cats.effect.{IO, Sync}
        import fs2.{io, text, Stream}
        import fs2._
        import fs2.io.tcp._
        import java.nio.file.{Path, Paths}
        import scala.concurrent.ExecutionContext
        import scala.concurrent.duration._
        import scala.concurrent.ExecutionContext.Implicits.global
        import java.net.InetSocketAddress
        // functional streams 2 to send TCP stream to local port
        val fileToStream = Paths.get("../../data/2_no_header.csv")

        val Fs = 10000
        val TCPPort = 54321

        /**
        Shouldn't really remain as a function when reduced to a call from the scheduler.
        */
        def tickSource[F[_]](period: FiniteDuration)(implicit s: Effect[F], t: Scheduler, ec: ExecutionContext): Stream[F,Unit] = {
            t.fixedRate(period)
        }

        /**
        Not really something that has any right to remain in the codebase.
        Sadly it's so ingrained now I cba removing it. Feel free to do so
        yourself!
        */
        def chunkify[F[_],I]: Pipe[F, Seq[I], I] = { // Seq is list

            def go(s: Stream[F,Seq[I]]): Pull[F,I,Unit] = {
                // take 1 array, transform it to stream
                s.pull.uncons1.flatMap {
                    case Some((segment, tl)) => // resultat av constructor Option
                        Pull.output(Segment.seq(segment)) >> go(tl) // bitshift. Call itself on tail
                    case None => Pull.done
                }
            }
            in => go(in).stream
        }

        /**
        Partitions a stream vectors of length n
        */
        def vectorize[F[_],I](length: Int): Pipe[F,I,Vector[I]] = {
            
            def go(s: Stream[F,I]): Pull[F,Vector[I],Unit] = {
                s.pull.unconsN(length.toLong, false).flatMap {
                    case Some((segment, tl)) => {
                        Pull.output1(segment.force.toVector) >> go(tl)
                    }
                    case None => Pull.done
                }
            }
            in => go(in).stream
        }

        def throttlerPipe[F[_]: Effect,I](Fs: Int, resolution: FiniteDuration)(implicit ec: ExecutionContext): Pipe[F,I,I] = {

            // determined optimal time resolution to be 
            // resolution: 0.125 seconds
            // when sampling rate is
            // Fs: 10000 Hz
            val ticksPerSecond = (1.second/resolution) // with a resolution of 0.125 seconds, ticksPerSecond will be 8
            val elementsPerTick = (Fs/ticksPerSecond).toInt // with a tickPerSecond of 8, this will be 1250 elementsPerTick

            _.through(vectorize(elementsPerTick)) // make vectors of segments of 1250 objects
                .zip(tickSource(resolution)) // here is the throttling. It is set to the given time resolution of 0.125 seconds.
                                             // This results in each segment (1250 object vector) being passed through
                                             // the pipe each 0.125 second.
                                             // This is the optimal near live / real-time pass-through speed of the vectors
                                             // given that the they (the segments segments) overlap 80 % in time. 
                                             // The 80 % overlap in time is later achieved using stridedSlide with 
                                             // windowWidth: 1250 and
                                             // overlap: 1000
                .through(_.map(_._1)) // remove the index from zip by only letting the actual data through
                .through(chunkify) // lastly, the throttled segments/vectors are flatMapped to a stream of integers
        }

        /**
        * Groups inputs in fixed size chunks by passing a "sliding window"
        * of size `width` and with an overlap `stride` over them.
        *
        * @example {{{
        * scala> Stream(1, 2, 3, 4, 5, 6, 7).stridedSliding(3, 1).toList
        * res0: List[Vector[Int]] = List(Vector(1, 2, 3), Vector(3, 4, 5), Vector(5, 6, 7))
        * }}}
        * @throws scala.IllegalArgumentException if `n` <= 0
        */
        def stridedSlide[F[_],I](windowWidth: Int, overlap: Int): Pipe[F,I,Vector[I]] = {
            require(windowWidth > 0,       "windowWidth must be > 0")
            require(windowWidth > overlap, "windowWidth must be wider than overlap")
            val stepsize = windowWidth - overlap
            def go(s: Stream[F,I], last: Vector[I]): Pull[F,Vector[I],Unit] = {
                s.pull.unconsN(stepsize.toLong, false).flatMap {
                    case Some((seg, tl)) =>
                        val forced = seg.force.toVector
                        Pull.output1(forced) >> go(tl, forced.drop(stepsize))
                    case None => Pull.done
                }
            }

            in => in.pull.unconsN(windowWidth.toLong, false).flatMap {
                case Some((seg, tl)) => {
                    val forced = seg.force.toVector
                    Pull.output1(forced) >> go(tl, forced.drop(stepsize))
                }
                case None => Pull.done
            }.stream
        }

        /**
        Encodes int to byte arrays. Assumes 4 bit integers
        */
        def intToBytes[F[_]]: Pipe[F, Int, Array[Byte]] = {

            def go(s: Stream[F, Int]): Pull[F,Array[Byte],Unit] = {
                s.pull.uncons flatMap {
                    case Some((seg, tl)) => {
                        val data = seg.force.toArray
                        println("done")
                        val bb = java.nio.ByteBuffer.allocate(data.length*4)
                        for(ii <- 0 until data.length){
                            bb.putInt(data(ii))
                        }
                        Pull.output(Segment(bb.array())) >> go(tl)
                    }
                    case None => Pull.done
                }
            }
            in => go(in).stream
        }

        def readCSV[F[_]: Effect](fileToStream: Path, Fs: Int)(implicit ec: ExecutionContext, s: Scheduler): Stream[F,Vector[Int]] = {
            println(s"elements per sec set to $Fs")
            val reader = io.file.readAll[F](fileToStream, 4096)
                .through(text.utf8Decode)
                .through(text.lines)
                
                //.through(_.map{ csvLine => csvLine.split(",").map(_.toInt).toList.tail})
                
                .through(_.map{ csvLine => csvLine.split(",").tail.apply(10).toInt}) // split each text (String) line 
                                                                                     // to an array of 61 Chars representing the
                                                                                     // TimeStamp and the electrode voltages, then      
                                                                                     // remove the TimeStamp using tail.
                                                                                     // Then select only electrode with ID=10 .
                                                                                     // The Char representing the integer value 
                                                                                     // of electrode with the selected ID
                                                                                     // is then converted to Int
                
                .through(throttlerPipe(Fs, 0.125.second)) // make the stream near live speed by slowing it down
                                                          // to segments of 1250 integers being delivered each 0.125 second.
                                                          // This is near live streaming throughput with a sampling rate of 
                                                          // Fs: 10000 Hz
            
                .through(stridedSlide(1250, 1000)) // transform the stream to sliding windows of segments of 1250 objects
                                                   // with 80 % overlap. The parameters fit optimally together with 
                                                   // throttlerPipe(10000 Hz, 0.125 seconds) as used above
                //.through(chunkify)
                //.through(vectorize(60*1250))
                //.through(vectorize(1250))
                //.through(throttlerPipe(Fs*60, 0.05.second))
            
                .handleErrorWith{
                    case e: java.lang.NumberFormatException => { println("Record done"); Stream.empty}
                    case e: Exception => { println(s"very bad error $e"); Stream.empty }
                    case _ => { println("I don't fuckin know..."); Stream.empty }
                }
            reader
        }

        val dataStream = readCSV[IO](fileToStream, Fs)

        // make fs2 socket server
        // start fs2 stream with
        // mystream.compile.drain.unsafeRunSync
        //

        // server: stream av stream av socket
        /*
        def sendStreamInTCP[F[_]](socket: Socket[F], dataStream: Stream[F, Vector[Int]]): Stream[F, Unit] = {
            val byteDataStream: Stream[F,Byte] = dataStream
                .through(chunkify)
                .through(intToBytes) // Stream[Int] to Stream[Array[Byte]]. TODO: Import. 1 Int = 4 bytes
                .through(_.map(_.toList))   
                .through(chunkify) // Stream[Byte]
            byteDataStream.through(socket.writes(None))
            
        }
        */

        def sendStreamInTCP[F[_]](socket: Socket[F], dataStream: Stream[F, Vector[Int]]): Stream[F, Unit] = {
            dataStream
                //.through(_.map(_.toString)) // convert the 80 % overlapping Vectors to String
                .through(_.map(_.mkString(","))) // convert the 80 % overlapping Vectors to String
                .intersperse("\n") // add newline between the windows now stored as Strings
                .through(text.utf8Encode)
                .through(socket.writes(None))
        }
        println(s"now starting the server on port $TCPPort")
        server[IO](new InetSocketAddress("localhost", TCPPort))
            .flatMap(stream => stream.flatMap(socket => sendStreamInTCP[IO](socket, dataStream)))
            .compile
            .drain
            .unsafeRunSync()
    }
}


