package org.atlasalign.plugin.project;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Function;
import org.atlasalign.application.AtlasReviewProvenance;
import org.atlasalign.application.ConstrainedLocalWarp2D;
import org.atlasalign.application.RegistrationInput;
import org.atlasalign.application.ReviewedTissueSupport;
import org.atlasalign.application.manual.BoundaryAuthoritativeTransform2D;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.application.manual.ManualOutlineWarp2D;
import org.atlasalign.application.manual.ReviewedOutlineTransform2D;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.plugin.export.ManualRoiExportService.ParentSourceContext;
import org.atlasalign.plugin.export.SourceSpaceExportService;

/** Explicit, bounded JSON binding. No default typing or Java object serialization. */
public final class ReviewProjectCodec {
    public static final String SCHEMA = "atlasalign-review-project-v1";
    public static final long MAXIMUM_FILE_BYTES = 128L * 1024 * 1024;
    private final ObjectMapper mapper;

    public record Header(ReviewProject.SourceReference source, String atlasCacheDirectory,
            SourceImageSnapshot sourceSnapshot, AtlasReviewProvenance atlas,
            RegistrationInput registrationInput, Optional<ParentSourceContext> parentSource) { }

    public ReviewProjectCodec() {
        mapper = JsonMapper.builder()
                .disable(MapperFeature.AUTO_DETECT_GETTERS, MapperFeature.AUTO_DETECT_IS_GETTERS)
                .visibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD,
                        com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(80).maxStringLength(64 * 1024 * 1024).build());
        final SimpleModule module = new SimpleModule("atlasalign-review-project-types-v1");
        addOptionals(module);
        bridge(module, Duration.class, String.class, Duration::toString, Duration::parse);
        bridge(module, BinaryMask.class, MaskValue.class,
                value -> new MaskValue(value.width(), value.height(),
                        Base64.getEncoder().encodeToString(value.copyBits().toByteArray())),
                value -> BinaryMask.fromBitSet(value.width(), value.height(),
                        BitSet.valueOf(Base64.getDecoder().decode(value.bits()))));
        bridge(module, ReviewedTissueSupport.class, SupportValue.class,
                value -> new SupportValue(value.sourceMask(), value.polygons(), value.controls(), value.sha256()),
                value -> {
                    final var restored = new ReviewedTissueSupport(value.sourceMask(), value.polygons(), value.controls());
                    if (!restored.sha256().equals(value.sha256())) {
                        throw new IllegalArgumentException("Saved tissue-crop geometry checksum does not match");
                    }
                    return restored;
                });
        bridge(module, ConstrainedLocalWarp2D.class, ConstrainedLocalWarp2D.Snapshot.class,
                ConstrainedLocalWarp2D::snapshot, ConstrainedLocalWarp2D::restore);
        bridge(module, ManualHemisphereWarp2D.class, ManualHemisphereWarp2D.Snapshot.class,
                ManualHemisphereWarp2D::snapshot, ManualHemisphereWarp2D::restore);
        bridge(module, ReviewedOutlineTransform2D.class, OutlineValue.class,
                this::outlineValue, this::restoreOutline);
        // Identical envelope for runtime concrete types and the sealed interface.
        bridge(module, ManualOutlineWarp2D.class, OutlineValue.class,
                this::outlineValue, value -> (ManualOutlineWarp2D) restoreOutline(value));
        bridge(module, BoundaryAuthoritativeTransform2D.class, OutlineValue.class,
                this::outlineValue, value -> (BoundaryAuthoritativeTransform2D) restoreOutline(value));
        mapper.registerModule(module);
    }

    public byte[] encode(final ReviewProject project) {
        try {
            final JsonNode payload = mapper.valueToTree(Objects.requireNonNull(project, "project"));
            final var root = mapper.createObjectNode();
            root.put("schema", SCHEMA);
            root.put("pluginVersion", SourceSpaceExportService.PLUGIN_VERSION);
            root.put("payloadSha256", sha256(mapper.writeValueAsBytes(payload)));
            root.set("payload", payload);
            final byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            if (bytes.length > MAXIMUM_FILE_BYTES) throw new IllegalArgumentException("Review project exceeds the supported file size");
            return bytes;
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalArgumentException("Could not encode the complete review project", error);
        }
    }

    /** Inspect identity references before any stored geometry is materialized. */
    public Header header(final byte[] bytes) {
        try {
            final JsonNode payload = checkedPayload(bytes);
            final JsonNode basis = payload.required("alignment").required("basis");
            return new Header(mapper.treeToValue(payload.required("source"), ReviewProject.SourceReference.class),
                    payload.required("atlasCacheDirectory").asText(),
                    mapper.treeToValue(basis.required("sourceSnapshot"), SourceImageSnapshot.class),
                    mapper.treeToValue(basis.required("atlas"), AtlasReviewProvenance.class),
                    mapper.treeToValue(payload.required("registrationInput"), RegistrationInput.class),
                    payload.required("parentSource").isNull() ? Optional.empty()
                            : Optional.of(mapper.treeToValue(payload.required("parentSource"), ParentSourceContext.class)));
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException("Could not read review-project identity references", error);
        }
    }

    public ReviewProject decode(final byte[] bytes) {
        try {
            return mapper.treeToValue(checkedPayload(bytes), ReviewProject.class);
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException("Review project is corrupt, unsupported, or contains invalid geometry", error);
        }
    }

    private JsonNode checkedPayload(final byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAXIMUM_FILE_BYTES) {
            throw new IllegalArgumentException("Review-project file size is invalid");
        }
        final JsonNode root = mapper.readTree(bytes);
        if (!SCHEMA.equals(root.required("schema").asText())) {
            throw new IllegalArgumentException("Unsupported review-project schema");
        }
        final JsonNode payload = root.required("payload");
        if (!root.required("payloadSha256").asText().equals(sha256(mapper.writeValueAsBytes(payload)))) {
            throw new IllegalArgumentException("Review-project checksum does not match; previous checkpoint remains usable");
        }
        return payload;
    }

    private OutlineValue outlineValue(final ReviewedOutlineTransform2D outline) {
        if (outline instanceof ManualOutlineWarp2D manual) {
            return new OutlineValue("manual-outline", mapper.valueToTree(manual.snapshot()));
        }
        if (outline instanceof BoundaryAuthoritativeTransform2D boundary) {
            return new OutlineValue("boundary-authoritative", mapper.valueToTree(boundary.snapshot()));
        }
        throw new IllegalArgumentException("Unsupported saved outline type");
    }

    private ReviewedOutlineTransform2D restoreOutline(final OutlineValue value) {
        try {
            return switch (value.kind()) {
                case "manual-outline" -> ManualOutlineWarp2D.restore(
                        mapper.treeToValue(value.data(), ManualOutlineWarp2D.Snapshot.class));
                case "boundary-authoritative" -> BoundaryAuthoritativeTransform2D.restore(
                        mapper.treeToValue(value.data(), BoundaryAuthoritativeTransform2D.Snapshot.class));
                default -> throw new IllegalArgumentException("Unsupported saved outline type");
            };
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not restore saved outline geometry", error);
        }
    }

    private record MaskValue(int width, int height, String bits) { }
    private record SupportValue(BinaryMask sourceMask, List<List<Point2D>> polygons,
            List<ReviewedTissueSupport.Control> controls, String sha256) { }
    private record OutlineValue(String kind, JsonNode data) { }

    private static <T, S> void bridge(final SimpleModule module, final Class<T> domain,
            final Class<S> stored, final Function<T, S> encode, final Function<S, T> decode) {
        module.addSerializer(domain, new JsonSerializer<T>() {
            @Override public void serialize(final T value, final JsonGenerator generator,
                    final SerializerProvider provider) throws IOException {
                provider.defaultSerializeValue(encode.apply(value), generator);
            }
        });
        module.addDeserializer(domain, new JsonDeserializer<T>() {
            @Override public T deserialize(final JsonParser parser, final DeserializationContext context) throws IOException {
                return decode.apply(context.readValue(parser, stored));
            }
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addOptionals(final SimpleModule module) {
        module.addSerializer((Class) Optional.class, new JsonSerializer<Optional<?>>() {
            @Override public void serialize(final Optional<?> value, final JsonGenerator generator,
                    final SerializerProvider provider) throws IOException {
                provider.defaultSerializeValue(value.orElse(null), generator);
            }
        });
        module.addDeserializer((Class) Optional.class, new OptionalValueDeserializer(null));
        module.addSerializer(OptionalDouble.class, new JsonSerializer<OptionalDouble>() {
            @Override public void serialize(final OptionalDouble value, final JsonGenerator generator,
                    final SerializerProvider provider) throws IOException {
                if (value.isPresent()) generator.writeNumber(value.getAsDouble()); else generator.writeNull();
            }
        });
        module.addDeserializer(OptionalDouble.class, new JsonDeserializer<OptionalDouble>() {
            @Override public OptionalDouble deserialize(final JsonParser parser, final DeserializationContext context) throws IOException {
                return OptionalDouble.of(parser.getDoubleValue());
            }
            @Override public OptionalDouble getNullValue(final DeserializationContext context) { return OptionalDouble.empty(); }
        });
    }

    private static final class OptionalValueDeserializer extends JsonDeserializer<Optional<?>> implements ContextualDeserializer {
        private final JavaType valueType;
        private OptionalValueDeserializer(final JavaType valueType) { this.valueType = valueType; }
        @Override public JsonDeserializer<?> createContextual(final DeserializationContext context,
                final BeanProperty property) {
            final JavaType type = context.getContextualType();
            return new OptionalValueDeserializer(type == null ? property.getType().containedTypeOrUnknown(0)
                    : type.containedTypeOrUnknown(0));
        }
        @Override public Optional<?> deserialize(final JsonParser parser, final DeserializationContext context) throws IOException {
            return Optional.ofNullable(context.readValue(parser, valueType));
        }
        @Override public Optional<?> getNullValue(final DeserializationContext context) { return Optional.empty(); }
    }

    public static String sha256(final byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required", impossible); }
    }
}
