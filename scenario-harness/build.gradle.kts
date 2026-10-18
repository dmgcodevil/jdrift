plugins { application }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
application { mainClass.set("dev.jdrift.scenarios.ScenarioRecorder") }
