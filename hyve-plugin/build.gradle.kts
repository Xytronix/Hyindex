

tasks.register("build") {
    dependsOn(":mcp-server:build", ":indexer-cli:build")
}
