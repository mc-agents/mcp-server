# The mc-agents development cluster: the operator, this server and a test server to point bots at.
#
#   make -C dev/cluster up        everything below, in order
#   make -C dev/cluster names     the MCP address and the command that registers it
#
# The cluster itself is created once and never by this file: `k3d cluster create mc-agents --agents 1`.
# Recreating it is the one step here that throws away a world, so it stays a deliberate command.
#
# Releases keep the names the cluster was first set up with (operator, mcp), so this upgrades what
# is running instead of installing a second copy beside it.
#
# Trying a local build means `kubectl set image` on one of them, which takes the image field away
# from Helm's server-side apply. --force-conflicts is how an install here puts the release back.
K3D_CLUSTER ?= mc-agents
CONTEXT     := k3d-$(K3D_CLUSTER)
NAMESPACE   := mc-agents
HERE        := $(abspath $(CURDIR))

KUBECTL := kubectl --context $(CONTEXT) -n $(NAMESPACE)
HELM    := helm --kube-context $(CONTEXT) -n $(NAMESPACE)

CHARTS                 := oci://junhyung.cloud/library/charts
OPERATOR_CHART_VERSION := 0.9.0-20260914025831.g8637fb66
SERVER_CHART_VERSION   := 0.48.0
# Pinned, not latest: the tag is latest-mc<version>, which Kubernetes does not treat as :latest, so
# a node that already pulled it once keeps running that build no matter what was published since.
FABRIC_TAG             := 0.56.0
AZALEA_TAG             := 0.8.0

# Loopback only. k3d binds 0.0.0.0 unless told otherwise, and the token is the only thing between
# that port and a bot that runs commands as op.
MCP_HOST_PORT := 13000

.NOTPARALLEL:
.PHONY: up port operator server paper plugin fixture names token

up: port operator server paper plugin fixture names

# Editing the load balancer recreates its container, so this runs only when the port is missing.
port:
	@docker port k3d-$(K3D_CLUSTER)-serverlb | grep -q '127.0.0.1:$(MCP_HOST_PORT)$$' || \
		k3d node edit k3d-$(K3D_CLUSTER)-serverlb --port-add 127.0.0.1:$(MCP_HOST_PORT):$(MCP_HOST_PORT)

# Helm installs crds/ once and never upgrades them, so a new bot kind would be refused by the enum
# the cluster was first given. `helm show` prints what it pulled ahead of the documents.
operator:
	$(KUBECTL) get namespace $(NAMESPACE) >/dev/null 2>&1 || kubectl --context $(CONTEXT) create namespace $(NAMESPACE)
	helm show crds $(CHARTS)/mc-agents-operator --version $(OPERATOR_CHART_VERSION) \
		| sed -n '/^---$$/,$$p' | kubectl --context $(CONTEXT) apply --server-side --force-conflicts -f -
	$(HELM) upgrade --install operator $(CHARTS)/mc-agents-operator \
		--version $(OPERATOR_CHART_VERSION) \
		--set watchNamespace=$(NAMESPACE) \
		--set bots.fabricTag=$(FABRIC_TAG) --set bots.azaleaTag=$(AZALEA_TAG) \
		--force-conflicts --wait --timeout 5m

# The chart will not invent a token, and a token in this file would be one every clone shares. So
# it is made here once, and upgrades read the same Secret.
token:
	@$(KUBECTL) get secret mcp-auth >/dev/null 2>&1 || \
		$(KUBECTL) create secret generic mcp-auth --from-literal=token="$$(openssl rand -hex 32)"

server: token
	$(HELM) upgrade --install mcp $(CHARTS)/mc-agents-mcp-server \
		--version $(SERVER_CHART_VERSION) \
		--set auth.token= --set auth.existingSecret=mcp-auth \
		--force-conflicts --wait --timeout 5m
	$(KUBECTL) apply -f $(HERE)/mcp-service.yaml

paper:
	$(KUBECTL) apply -f $(HERE)/paper.yaml
	$(KUBECTL) rollout status deploy/paper --timeout 10m

# The fixture plugin: what the server received from a bot's keys, and the conversation and the bite
# that press-input is tried against. Paper loads plugins only at start, so this restarts the server;
# the world and the datapack are on the claim and come back with it.
plugin:
	cd $(HERE)/../.. && ./gradlew -q :fixture-plugin:jar
	$(KUBECTL) exec deploy/paper -- mkdir -p /data/plugins
	$(KUBECTL) cp $(HERE)/../fixture-plugin/build/libs/mcagents-fixture.jar \
		$$($(KUBECTL) get pod -l app.kubernetes.io/name=paper -o name | cut -d/ -f2):/data/plugins/mcagents-fixture.jar
	$(KUBECTL) rollout restart deploy/paper
	$(KUBECTL) rollout status deploy/paper --timeout 10m

# Copied rather than baked into an image, so a fixture edit is one `make fixture` away. The claim
# under /data keeps it across restarts. The pack's load tag runs mcagents:setup, so the reload both
# makes the pack exist and builds the fixture.
fixture:
	$(KUBECTL) exec deploy/paper -- rm -rf /data/world/datapacks/mcagents
	$(KUBECTL) cp $(HERE)/../fixture $$($(KUBECTL) get pod -l app.kubernetes.io/name=paper -o name | cut -d/ -f2):/data/world/datapacks/mcagents
	$(KUBECTL) exec deploy/paper -- rcon-cli reload

names:
	@echo
	@echo "  mcp     http://127.0.0.1:$(MCP_HOST_PORT)/mcp"
	@echo "  server  paper.$(NAMESPACE).svc:25565   (the host join-server is given)"
	@echo
	@echo "  claude mcp add --scope user --transport http mc-agents http://127.0.0.1:$(MCP_HOST_PORT)/mcp \\"
	@echo "    -H \"Authorization: Bearer \$$($(KUBECTL) get secret mcp-auth -o jsonpath='{.data.token}' | base64 -d)\""
	@echo
