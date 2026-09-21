# The mc-agents development cluster: the operator, this server and a test server to point bots at.
#
#   make -C dev/cluster up        everything below, in order
#   make -C dev/cluster names     the MCP address and the command that registers it
#
# The cluster itself is created once and never by this file: `k3d cluster create mc-agents --agents 1`.
# Recreating it is the one step here that throws away a world, so it stays a deliberate command.
#
# The layout is the one every tenant gets. The operator lives alone in mc-agents-system; the namespace
# mc-agents is the tenant, holding the test server, an MCPServer and the bots it starts. Which image
# tags those bots run is mcpserver.yaml's MinecraftBotProfile, not a flag here.
K3D_CLUSTER ?= mc-agents
CONTEXT     := k3d-$(K3D_CLUSTER)
SYSTEM      := mc-agents-system
NAMESPACE   := mc-agents
HERE        := $(abspath $(CURDIR))

KUBECTL := kubectl --context $(CONTEXT) -n $(NAMESPACE)
HELM    := helm --kube-context $(CONTEXT) -n $(SYSTEM)

CHARTS                 := oci://junhyung.cloud/mc-agents/charts
OPERATOR_CHART_VERSION := 0.15.0

# Loopback only. k3d binds 0.0.0.0 unless told otherwise, and the token is the only thing between
# that port and a bot that runs commands as op.
MCP_HOST_PORT := 13000

.NOTPARALLEL:
.PHONY: up port operator server paper plugin fixture copy-plugin copy-fixture restart-paper names token

# Both copies go in before the one restart they share: each needs Paper to start again to be read.
up: port operator server paper copy-plugin copy-fixture restart-paper names

# Editing the load balancer recreates its container, so this runs only when the port is missing.
port:
	@docker port k3d-$(K3D_CLUSTER)-serverlb | grep -q '127.0.0.1:$(MCP_HOST_PORT)$$' || \
		k3d node edit k3d-$(K3D_CLUSTER)-serverlb --port-add 127.0.0.1:$(MCP_HOST_PORT):$(MCP_HOST_PORT)

# Since operator 0.13 the chart renders its CRDs as release resources, so an upgrade carries the
# schema and nothing is applied by hand first. --reset-values: an upgrade otherwise keeps the --set
# of whatever installed it last, which after `make k3d-deploy` in the operator repository is a
# local image that is not the pinned release. --force-conflicts: Helm 4 applies server-side, and
# the CRDs this cluster had from before 0.13 name kubectl as the field manager of their schema.
operator:
	$(HELM) upgrade --install mc-agents-operator $(CHARTS)/mc-agents-operator \
		--version $(OPERATOR_CHART_VERSION) --create-namespace --reset-values \
		--force-conflicts --wait --timeout 5m

# The token is made here once rather than by the operator, so the one Claude Code is registered with
# survives deleting and recreating the MCPServer.
token:
	@kubectl --context $(CONTEXT) get namespace $(NAMESPACE) >/dev/null 2>&1 || \
		kubectl --context $(CONTEXT) create namespace $(NAMESPACE)
	@$(KUBECTL) get secret mcp-auth >/dev/null 2>&1 || \
		$(KUBECTL) create secret generic mcp-auth --from-literal=token="$$(openssl rand -hex 32)"

server: token
	$(KUBECTL) apply -f $(HERE)/mcpserver.yaml
	$(KUBECTL) wait mcpserver/mc-agents --for=condition=Ready --timeout 5m
	$(KUBECTL) apply -f $(HERE)/mcp-service.yaml

paper:
	$(KUBECTL) apply -f $(HERE)/paper.yaml
	$(KUBECTL) rollout status deploy/paper --timeout 10m

# The fixture plugin: what the server received from a bot's keys, and the conversation and the bite
# that press-input is tried against. Paper loads plugins only at start.
plugin: copy-plugin restart-paper

copy-plugin:
	cd $(HERE)/../.. && ./gradlew -q :fixture-plugin:jar
	$(KUBECTL) exec deploy/paper -- mkdir -p /data/plugins
	$(KUBECTL) cp $(HERE)/../fixture-plugin/build/libs/mcagents-fixture.jar \
		$$($(KUBECTL) get pod -l app.kubernetes.io/name=paper -o name | cut -d/ -f2):/data/plugins/mcagents-fixture.jar

# Copied rather than baked into an image, so a fixture edit is one `make fixture` away. Restarted
# rather than reloaded: /reload re-reads functions and advancements, but a dialog, like everything else
# held in a registry, is read only when the server starts, and a reload left `dialog show` unable to
# find one the pack had just gained. The pack's load tag runs mcagents:setup on the start.
fixture: copy-fixture restart-paper

copy-fixture:
	$(KUBECTL) exec deploy/paper -- rm -rf /data/world/datapacks/mcagents
	$(KUBECTL) cp $(HERE)/../fixture $$($(KUBECTL) get pod -l app.kubernetes.io/name=paper -o name | cut -d/ -f2):/data/world/datapacks/mcagents

# The world, the plugin and the datapack are on the claim under /data and come back with the server.
restart-paper:
	$(KUBECTL) rollout restart deploy/paper
	$(KUBECTL) rollout status deploy/paper --timeout 10m

names:
	@echo
	@echo "  mcp     http://127.0.0.1:$(MCP_HOST_PORT)/mcp"
	@echo "  server  paper.$(NAMESPACE).svc:25565   (the host join-server is given)"
	@echo
	@echo "  claude mcp add --scope user --transport http mc-agents http://127.0.0.1:$(MCP_HOST_PORT)/mcp \\"
	@echo "    -H \"Authorization: Bearer \$$($(KUBECTL) get secret mcp-auth -o jsonpath='{.data.token}' | base64 -d)\""
	@echo
