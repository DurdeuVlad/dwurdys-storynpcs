package com.storynpcs.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.Objects;
import java.util.UUID;

/** Shared bounded codecs for canonical mutation metadata and payload envelopes. */
public final class MutationProtocolCodecs {
    /** Payload schema carried inside every StoryNPC packet. */
    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_PACKET_BYTES = 64 * 1024;
    public static final int MAX_EDITOR_DOCUMENT_BYTES = 1 << 20;
    public static final int MAX_REGISTRY_DOCUMENT_BYTES = 4 << 20;
    public static final int MAX_ROLE_PAYLOAD_BYTES = 2 << 20;
    public static final int MAX_ID_CHARS = 256;
    public static final int MAX_TEXT_CHARS = 16 * 1024;
    public static final int MAX_MESSAGE_CHARS = 4 * 1024;
    public static final int MAX_LIST_ITEMS = 256;

    public static final StreamCodec<ByteBuf, UUID> UUID_CODEC = new StreamCodec<>() {
        @Override
        public UUID decode(ByteBuf buffer) {
            return new UUID(buffer.readLong(), buffer.readLong());
        }

        @Override
        public void encode(ByteBuf buffer, UUID value) {
            buffer.writeLong(value.getMostSignificantBits());
            buffer.writeLong(value.getLeastSignificantBits());
        }
    };

    public static final StreamCodec<ByteBuf, String> ID_CODEC = ByteBufCodecs.stringUtf8(MAX_ID_CHARS);
    public static final StreamCodec<ByteBuf, String> TEXT_CODEC = ByteBufCodecs.stringUtf8(MAX_TEXT_CHARS);
    public static final StreamCodec<ByteBuf, String> MESSAGE_CODEC = ByteBufCodecs.stringUtf8(MAX_MESSAGE_CHARS);
    public static final StreamCodec<ByteBuf, String> JSON_CODEC = ByteBufCodecs.stringUtf8(MAX_EDITOR_DOCUMENT_BYTES);
    public static final StreamCodec<ByteBuf, String> REGISTRY_JSON_CODEC =
            ByteBufCodecs.stringUtf8(MAX_REGISTRY_DOCUMENT_BYTES);
    public static final StreamCodec<ByteBuf, java.util.List<String>> STRING_LIST_CODEC =
            TEXT_CODEC.apply(ByteBufCodecs.list(MAX_LIST_ITEMS));

    /** Prefixes a payload with the schema version and bounds its total encoded size. */
    public static <T> StreamCodec<ByteBuf, T> versioned(StreamCodec<ByteBuf, T> delegate) {
        return versioned(delegate, MAX_PACKET_BYTES);
    }

    public static <T> StreamCodec<ByteBuf, T> editorPayload(StreamCodec<ByteBuf, T> delegate) {
        return versioned(delegate, MAX_EDITOR_DOCUMENT_BYTES);
    }

    public static <T> StreamCodec<ByteBuf, T> registryPayload(StreamCodec<ByteBuf, T> delegate) {
        return versioned(delegate, MAX_REGISTRY_DOCUMENT_BYTES);
    }

    public static <T> StreamCodec<ByteBuf, T> rolePayload(StreamCodec<ByteBuf, T> delegate) {
        return versioned(delegate, MAX_ROLE_PAYLOAD_BYTES);
    }

    private static <T> StreamCodec<ByteBuf, T> versioned(StreamCodec<ByteBuf, T> delegate, int maxBytes) {
        Objects.requireNonNull(delegate, "delegate");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        return StreamCodec.of(
                (buf, value) -> {
                    int start = buf.writerIndex();
                    try {
                        ByteBufCodecs.VAR_INT.encode(buf, PROTOCOL_VERSION);
                        delegate.encode(buf, value);
                        if (buf.writerIndex() - start > maxBytes) {
                            throw new IllegalArgumentException("StoryNPC payload exceeds " + maxBytes + " bytes");
                        }
                    } catch (RuntimeException ex) {
                        buf.writerIndex(start);
                        throw ex;
                    }
                },
                buf -> {
                    int start = buf.readerIndex();
                    try {
                        if (buf.readableBytes() > maxBytes) {
                            throw new IllegalArgumentException("StoryNPC payload exceeds " + maxBytes + " bytes");
                        }
                        int version = ByteBufCodecs.VAR_INT.decode(buf);
                        if (version != PROTOCOL_VERSION) {
                            throw new IllegalArgumentException("Unsupported StoryNPC payload version: " + version);
                        }
                        T value = delegate.decode(buf);
                        if (buf.readerIndex() - start > maxBytes) {
                            throw new IllegalArgumentException("StoryNPC payload exceeds " + maxBytes + " bytes");
                        }
                        if (buf.isReadable()) {
                            throw new IllegalArgumentException("Trailing bytes in StoryNPC payload");
                        }
                        return value;
                    } catch (RuntimeException ex) {
                        buf.readerIndex(start);
                        throw ex;
                    }
                }
        );
    }

    private MutationProtocolCodecs() {}
}
