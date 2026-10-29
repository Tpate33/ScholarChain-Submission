// sbt plugins. Every version is read from the single version ledger
// project/versions.properties; no version literal appears here.
// The ledger is resolved from this meta-build's base directory (the repository's
// project/), never from the JVM working directory. Floating values are rejected
// while the meta-build's settings load, before any plugin is resolved.
// project/*.scala is not visible to this file, so the reader is inlined. Its
// rejection rules must stay identical to Ledger.floating in project/Ledger.scala.
def ledgerVersion(metaBase: File, key: String): String = {
  val ledgerFile = new File(metaBase, "versions.properties").getCanonicalFile
  if (!ledgerFile.isFile) sys.error(s"Version ledger not found: ${ledgerFile.getPath}")
  val props = new java.util.Properties()
  val in = new java.io.FileInputStream(ledgerFile)
  try props.load(in)
  finally in.close()
  val value = Option(props.getProperty(key))
    .map(_.trim)
    .getOrElse(sys.error(s"Version ledger ${ledgerFile.getPath} has no value for '$key'"))
  val lower = value.toLowerCase(java.util.Locale.ROOT)
  val forbiddenChars = "+[](),*{}<>$? \t".toSet
  if (
    value.isEmpty || lower.contains("latest") || lower.contains("snapshot") ||
    lower == "release" || value.exists(forbiddenChars.contains)
  )
    sys.error(s"Version ledger ${ledgerFile.getPath} has a floating or invalid value: $key=$value")
  value
}

// Same expansion as addSbtPlugin, with the version taken from the ledger at setting time.
def ledgerPlugin(org: String, name: String, key: String): Setting[Seq[ModuleID]] =
  libraryDependencies += Defaults.sbtPluginExtra(
    org % name % ledgerVersion(baseDirectory.value, key),
    (pluginCrossBuild / sbtBinaryVersion).value,
    (update / scalaBinaryVersion).value
  )

ledgerPlugin("org.scalameta", "sbt-scalafmt", "plugin.sbt-scalafmt")
ledgerPlugin("com.eed3si9n", "sbt-assembly", "plugin.sbt-assembly")
ledgerPlugin("com.github.sbt", "sbt-native-packager", "plugin.sbt-native-packager")
