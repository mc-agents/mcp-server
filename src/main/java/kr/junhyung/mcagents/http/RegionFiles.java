package kr.junhyung.mcagents.http;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.junhyung.mcagents.tool.CustomBlocks;
import kr.junhyung.mcagents.tool.RegionStore;
import kr.junhyung.mcagents.tool.RegionSurvey;
import kr.junhyung.mcagents.tool.Schematic;
import kr.junhyung.mcagents.tool.Snapshot;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A kept region as a file, in and out.
 *
 * <p>A tool call carries JSON, and a schematic is neither small nor text: a hall is fifty
 * kilobytes gzipped and a survey a few megabytes, which is nothing to an HTTP body and everything
 * to a context window. So a region is downloaded and uploaded here, behind the same bearer token as
 * /mcp, with curl and the token the agent already has. The tools say the paths.
 */
@RestController
public class RegionFiles {

    /** The most a file may be on the wire, which is well past the store's own limit in blocks. */
    static final int MAX_UPLOAD_BYTES = 32 * 1024 * 1024;

    private final RegionStore store;
    private final CustomBlocks customBlocks;

    public RegionFiles(RegionStore store, CustomBlocks customBlocks) {
        this.store = store;
        this.customBlocks = customBlocks;
    }

    @GetMapping(value = "/regions/{id}.schem", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> download(@PathVariable String id) throws IOException {
        Snapshot region = store.get(id);

        if (region == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"no region is kept as %s\"}".formatted(id).getBytes());
        }

        /* A custom block as WorldEdit files it, when this server learned what the region's server calls them. */
        CustomBlocks.Dictionary learned = customBlocks.of(region.origin());
        byte[] file = Schematic.write(region, null, block -> {
            CustomBlocks.Entry custom = learned == null ? null : learned.byId(block);
            return custom == null || custom.internal() == null ? block : custom.internal();
        });

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"%s.schem\"".formatted(id))
                .header("X-Region-Unread", Long.toString(region.unread()))
                .body(file);
    }

    /**
     * A schematic file kept as a region, answered with its id.
     *
     * @param name what list-regions calls it; the file's own name when it has one, otherwise none
     */
    @PostMapping(value = "/regions", consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@RequestBody byte[] file,
            @RequestParam(required = false) String name) {
        if (file.length > MAX_UPLOAD_BYTES) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .body(Map.of("error", "the file is %d bytes, and %d is the most taken".formatted(file.length, MAX_UPLOAD_BYTES)));
        }

        Snapshot region;

        try {
            region = Schematic.read(file, store.fresh(), name, RegionSurvey.MAX_BLOCKS, Instant.now(), "uploaded schematic");
        } catch (IOException unreadable) {
            return ResponseEntity.badRequest().body(Map.of("error", "not a schematic this server reads: " + unreadable.getMessage()));
        }

        List<String> evicted = store.keep(region);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", region.id(),
                "blocks", region.blocks(),
                "palette", region.palette().size(),
                "evicted", evicted));
    }
}
