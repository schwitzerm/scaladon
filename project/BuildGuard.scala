import sbt.*
import sbt.Keys.*

/** A resolved-classpath guard for the module invariants.
  *
  * Jar-name prefixes are sufficient because every forbidden library enters the graph through one of
  * these roots: a leaf such as `algebra_3` can only arrive via `cats-core`, which is already
  * rejected. The task reads both the compile and the test classpath, so a test helper cannot smuggle
  * Cats into a Cats-free module.
  */
object BuildGuard:

  val dependencyGuard =
    taskKey[Unit]("Fail when a forbidden artifact is on this module's classpath")

  val forbiddenArtifacts =
    settingKey[Seq[String]]("Jar-name prefixes this module must not resolve")

  def settings(forbidden: Seq[String]): Seq[Setting[?]] =
    Seq(
      forbiddenArtifacts := forbidden,
      dependencyGuard := Def.uncached {
        val jars =
          ((Compile / externalDependencyClasspath).value ++
            (Test / externalDependencyClasspath).value).map(_.data.name).distinct
        val rules = forbiddenArtifacts.value
        val hits  = jars.flatMap(jar => rules.find(jar.startsWith).map(rule => s"$rule -> $jar"))
        if hits.nonEmpty then
          sys.error(s"forbidden artifacts on the classpath:\n  ${hits.mkString("\n  ")}")
      },
      Compile / compile := (Compile / compile).dependsOn(dependencyGuard).value
    )
