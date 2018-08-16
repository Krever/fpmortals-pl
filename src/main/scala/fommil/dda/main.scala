// Copyright: 2017 - 2018 Sam Halliday
// License: http://www.gnu.org/licenses/gpl-3.0.en.html

package fommil
package dda

import prelude._, Z._

import scala.collection.immutable.List

import org.http4s.client.blaze.BlazeClientConfig
import pureconfig.orphans._
import scalaz.ioeffect.console._

import logic._
import interpreters._
import http._
import http.interpreters._
import http.oauth2._
import http.oauth2.interpreters._
import time._

object Main extends SafeApp {

  def run(args: List[String]): IO[Void, ExitStatus] = {
    if (args.contains("--machines")) auth("machines")
    else if (args.contains("--drone")) auth("drone")
    else agents(BearerToken("<invalid>", Epoch(0)))
  }.attempt[Void].map {
    case \/-(_) => ExitStatus.ExitNow(0)
    case -\/(_) => ExitStatus.ExitNow(1)
  }

  // performs the OAuth 2.0 dance to obtain refresh tokens
  def auth(name: String): Task[Unit] =
    for {
      config    <- readConfig[ServerConfig](name + ".server")
      ui        <- BlazeUserInteraction()
      auth      = new AuthModule(config)(ui)
      codetoken <- auth.authenticate.eval(Tokens())
      bconfig   <- readConfig[BlazeClientConfig]("blaze")
      client    <- BlazeJsonClient(bconfig)
      token <- {
        type HT[f[_], a] = EitherT[f, JsonClient.Error, a]
        type H[a]        = HT[Task, a]

        val T: LocalClock[H] = LocalClock.liftM(new LocalClockTask)
        val access           = new AccessModule(config)(client, T)

        access.access(codetoken)
      }.run.swallowError
      _ <- putStrLn(z"got token: ${token._2}").toTask
    } yield ()

  // runs the app, requires that refresh tokens are provided
  def agents(bearer: BearerToken): Task[Unit] = {
    type HT[f[_], a] = EitherT[f, JsonClient.Error, a]
    type GT[f[_], a] = StateT[f, BearerToken, a]

    type H[a] = HT[Task, a]
    type G[a] = GT[H, a]

    val T: LocalClock[G] = {
      import LocalClock.liftM
      liftM(liftM(new LocalClockTask))
    }

    // TODO: should we have an outer layer that is just Task ?

    for {
      config   <- readConfig[AppConfig].liftM[HT].liftM[GT]
      blaze    <- BlazeJsonClient(config.blaze).liftM[HT].liftM[GT]
      hblaze   = JsonClient.liftM[H, GT](blaze)
      drone    = new DroneModule(oauth(config.drone)(hblaze, T))
      machines = new MachinesModule(oauth(config.machines)(hblaze, T))
      agents   = new DynAgentsModule(drone, machines)
      start    <- agents.initial
      _ <- {
        type FT[f[_], a] = StateT[f, WorldView, a]
        type F[a]        = FT[G, a]
        val F: MonadState[F, WorldView] = MonadState[F, WorldView]
        val A: DynAgents[F]             = DynAgents.liftM(agents)
        val S: Sleep[F] = {
          import Sleep.liftM
          liftM(liftM(liftM(new SleepTask)))
        }

        for {
          old     <- F.get
          updated <- A.update(old)
          changed <- A.act(updated)
          _       <- F.put(changed)
          _       <- S.sleep(10.seconds)
        } yield ()
      }.forever[Unit].run(start)
    } yield ()
  }.eval(bearer).run.swallowError

  private def oauth[M[_]](
    config: OAuth2Config
  )(
    H: JsonClient[M],
    T: LocalClock[M]
  )(
    implicit
    M: MonadError[M, JsonClient.Error],
    S: MonadState[M, BearerToken]
  ): AuthJsonClient[M] =
    new AuthJsonClientModule[M](config.token)(
      H,
      T,
      new RefreshModule(config.server)(H, T)(M)
    )

  @deriving(ConfigReader)
  final case class OAuth2Config(
    token: RefreshToken,
    server: ServerConfig
  )
  @deriving(ConfigReader)
  final case class AppConfig(
    drone: OAuth2Config,
    machines: OAuth2Config,
    blaze: BlazeClientConfig = BlazeClientConfig.defaultConfig
  )
}
