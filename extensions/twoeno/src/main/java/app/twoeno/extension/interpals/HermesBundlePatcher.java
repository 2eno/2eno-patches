package app.twoeno.extension.interpals;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns off the default ad placements compiled into the Hermes bytecode bundle of InterPals.
 * <p>
 * Only exact byte patterns of a known Hermes bytecode version are changed. If a pattern is not found
 * exactly once, it is not changed, so an unknown bundle is never broken.
 */
final class HermesBundlePatcher {
    private static final byte[] HERMES_MAGIC = {(byte) 0xC6, 0x1F, (byte) 0xBC, 0x03, (byte) 0xC1, 0x03, 0x19, 0x1F};
    private static final int SUPPORTED_VERSION = 98;
    /**
     * The bundle ends with the SHA-1 hash of everything before it.
     */
    private static final int FOOTER_HASH_SIZE = 20;

    private static final byte[] AD_MARKER = "album_swiper_ad".getBytes();
    /**
     * The instruction loading the default enabled state of the album swiper ad.
     * Replacing its opcode with {@link #DISABLED_OPCODE} disables the ad by default.
     */
    private static final byte[] SWIPER_DEFAULT = {0x11, 0x72, 0x03, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00};
    private static final byte DISABLED_OPCODE = 0x21;

    private static final byte LOAD_CONST_INT = (byte) 0x8C;
    /**
     * How long the ad config is cached, 6 hours.
     */
    private static final int CACHE_TTL_MS = 6 * 60 * 60 * 1000;
    /**
     * Cache the config only for a minute, so the filtered config from the server is used soon.
     */
    private static final int PATCHED_CACHE_TTL_MS = 60 * 1000;

    static final class Result {
        /**
         * The patched bundle, or null if it was not patched.
         */
        final byte[] bundle;
        final String description;

        private Result(byte[] bundle, String description) {
            this.bundle = bundle;
            this.description = description;
        }
    }

    private HermesBundlePatcher() {
    }

    static Result patch(byte[] bundle) throws Exception {
        if (bundle.length < HERMES_MAGIC.length + 4 + FOOTER_HASH_SIZE
                || !Arrays.equals(Arrays.copyOfRange(bundle, 0, HERMES_MAGIC.length), HERMES_MAGIC)) {
            return new Result(null, "not a Hermes bundle");
        }
        int version = readInt(bundle, HERMES_MAGIC.length);
        if (version != SUPPORTED_VERSION) {
            return new Result(null, "Hermes bytecode version " + version);
        }
        if (indexesOf(bundle, AD_MARKER, 1).isEmpty()) {
            return new Result(null, "no album_swiper_ad");
        }

        byte[] patched = bundle.clone();
        List<String> changes = new ArrayList<>();

        List<Integer> swiperDefaults = indexesOf(bundle, SWIPER_DEFAULT, 2);
        if (swiperDefaults.size() == 1) {
            patched[swiperDefaults.get(0)] = DISABLED_OPCODE;
            changes.add("swiper default @" + swiperDefaults.get(0));
        }

        List<Integer> cacheTtls = new ArrayList<>();
        for (int index : indexesOf(bundle, int32(CACHE_TTL_MS), Integer.MAX_VALUE)) {
            // LoadConstInt <register> <value>
            if (index >= 2 && bundle[index - 2] == LOAD_CONST_INT) cacheTtls.add(index);
        }
        if (cacheTtls.size() == 1) {
            System.arraycopy(int32(PATCHED_CACHE_TTL_MS), 0, patched, cacheTtls.get(0), 4);
            changes.add("cache ttl @" + cacheTtls.get(0));
        }

        if (changes.isEmpty()) {
            return new Result(null, "swiper default found " + swiperDefaults.size()
                    + "x, cache ttl " + cacheTtls.size() + "x, expected once");
        }

        int hashOffset = patched.length - FOOTER_HASH_SIZE;
        byte[] hash = MessageDigest.getInstance("SHA-1").digest(Arrays.copyOfRange(patched, 0, hashOffset));
        System.arraycopy(hash, 0, patched, hashOffset, FOOTER_HASH_SIZE);
        StringBuilder description = new StringBuilder();
        for (String change : changes) {
            if (description.length() > 0) description.append(", ");
            description.append(change);
        }
        return new Result(patched, description.toString());
    }

    private static byte[] int32(int value) {
        return new byte[]{(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8
                | (data[offset + 2] & 0xFF) << 16 | (data[offset + 3] & 0xFF) << 24;
    }

    private static List<Integer> indexesOf(byte[] data, byte[] pattern, int limit) {
        List<Integer> indexes = new ArrayList<>();
        outer:
        for (int i = 0; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            indexes.add(i);
            if (indexes.size() >= limit) break;
        }
        return indexes;
    }
}
