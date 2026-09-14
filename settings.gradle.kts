rootProject.name = "mcp-server"

/*
The end-to-end suite is a project of its own because it is a different kind of thing: it compiles
against nothing here, drives the server as the process a release ships, and needs Docker and
minutes. Tagging it inside the server's own tests would have left `test` deciding what to skip.
*/
include("e2e")

/*
A Paper plugin the suite loads into its server, so a case can ask the server what input it received.
It sits with the datapack under dev/ because it is the same kind of thing: a world to test against.
*/
include("fixture-plugin")
project(":fixture-plugin").projectDir = file("dev/fixture-plugin")
