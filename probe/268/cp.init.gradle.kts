allprojects {
    if (path == ":composegl-demo-snake") {
        afterEvaluate {
            val ss = extensions.getByType<SourceSetContainer>()
            tasks.register("printProbeCp") {
                dependsOn(ss["main"].runtimeClasspath)
                val cp = ss["main"].runtimeClasspath
                val launcher = tasks.named<JavaExec>("probe").flatMap { it.javaLauncher }
                doLast {
                    println("JAVA=" + launcher.get().executablePath.asFile.absolutePath)
                    println("CP=" + cp.asPath)
                }
            }
        }
    }
}
