rootProject.name = "mcp-server"

/*
The end-to-end suite is a project of its own because it is a different kind of thing: it compiles
against nothing here, drives the server as the process a release ships, and needs Docker and
minutes. Tagging it inside the server's own tests would have left `test` deciding what to skip.
*/
include("e2e")
