# The compose deployment's one step compose cannot do itself: two secrets, made once.
#
#   make -C deploy/compose up      write .env if it is missing, then start everything
#   make -C deploy/compose names   the MCP address and the command that registers it
#   make -C deploy/compose down    stop, keeping the client assets and any login cache
#
# Everything else is `docker compose` in this directory -- `--scale`, `run`, `logs`, `ps`. The
# compose file is the deployment; this is not a wrapper around it.
COMPOSE := docker compose

.PHONY: up down names tokens
# A token half written is worse than none: .env would exist, make would never write it again, and
# compose would refuse to start with a message about a variable the file appears to set.
.DELETE_ON_ERROR:

up: .env tokens
	$(COMPOSE) up -d
	@$(MAKE) --no-print-directory names

# Compose refuses a variable that is empty. The server refuses one that is blank, which is not the
# same word: a token edited down to a space gets past `$${VAR:?}`, reaches the server, and is read
# as no token at all -- the auth filter comes off /mcp and the bot link stops asking. The two
# definitions are checked against each other here, where the answer can say which file to look in.
tokens: .env
	@set -a && . ./.env && set +a && \
		for name in MCP_AUTH_TOKEN BOT_LINK_TOKEN; do \
			eval "value=\$$$$name"; \
			case "$$value" in \
				*[![:space:]]*) ;; \
				*) echo "$$name is blank in $(CURDIR)/.env. A blank token is not a weak gate, it is no gate: delete the file and run \`make up\` to write both again." >&2; exit 1 ;; \
			esac; \
		done

# Both tokens, generated once and then left alone, which is the operator's ensureSecret rule: a
# token an agent is already registered with, or a running bot already holds, has to keep working
# across every later `up`. The chart refuses to generate tokens because a value that changes per
# render leaves GitOps out of sync; nothing renders this file twice, so that reason does not apply
# and the operator is the model to copy instead.
#
# A file target is the whole of the idempotency: make writes it when it is absent and never when it
# is not. Rotating a token is deleting .env and recreating everything that holds one.
#
# 32 random bytes as hex, which is what the operator generates and what dev/cluster creates by hand.
.env:
	@umask 077 && { \
		echo "# Written once by \`make up\`. Both tokens are secrets: this file is gitignored,"; \
		echo "# and it is read by compose, not by the shell."; \
		echo "#"; \
		echo "# Rotating one is deleting this file, running \`make up\`, and registering the new"; \
		echo "# token with every agent. Editing a line out instead leaves compose refusing to"; \
		echo "# start, which is the intended failure: a blank token is not an absent gate, it is"; \
		echo "# no gate at all."; \
		echo "#"; \
		echo "# MCP_HOST_PORT may be added here to publish /mcp somewhere other than 3000."; \
		echo "MCP_AUTH_TOKEN=$$(openssl rand -hex 32)"; \
		echo "BOT_LINK_TOKEN=$$(openssl rand -hex 32)"; \
	} > $@
	@echo "wrote $(CURDIR)/$@"

# Read out of .env rather than out of a running container, so it answers before the first `up` and
# after a `down`.
names: .env
	@set -a && . ./.env && set +a && \
		port="$${MCP_HOST_PORT:-3000}" && \
		echo && \
		echo "  mcp     http://127.0.0.1:$$port/mcp" && \
		echo "  bots    mcp-server:8765   (inside the compose network, not published)" && \
		echo && \
		echo "  claude mcp add --scope user --transport http mc-agents http://127.0.0.1:$$port/mcp \\" && \
		echo "    -H \"Authorization: Bearer $$MCP_AUTH_TOKEN\"" && \
		echo

# Keeps the volumes, so the next `up` does not re-download 200-600MB of Minecraft assets. `down -v`
# throws those away, and any Microsoft credential cache with them.
down:
	$(COMPOSE) down
