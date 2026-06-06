package civil.aura;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;

public final class AuraPlayerWallRenderer {

    private AuraPlayerWallRenderer() {}

    // ========== Color: foreign civilization walls (deep orange) ==========
    private static final float WALL_R = 0.92f, WALL_G = 0.45f, WALL_B = 0.20f;

    // ========== Timing ==========
    private static final long SONAR_DELAY_NS = (long) (1.2 * 1_000_000_000L);
    private static final float FADE_IN_RATE = 1.0f / 0.6f;
    private static final float FADE_OUT_RATE = 1.0f / 2.0f;
    private static final float FACE_FADE_IN_S = 0.6f;

    // ========== Alpha ==========
    private static final float BASE_ALPHA = 0.55f;

    // ========== Breathing ==========
    private static final float BREATHE_AMP = 0.08f;
    private static final float BREATHE_SPEED = 1.5f;

    // ========== Texture ==========
    private static final Identifier FORCEFIELD_TEXTURE =
            Identifier.fromNamespaceAndPath("minecraft", "textures/misc/forcefield.png");

    // ========== GPU ==========
    private static final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(65536);
    private static MappableRingBuffer vertexBuffer;

    // ========== Face identity ==========
    private record FaceId(int axis, long planeCoord, long minU) {
        static FaceId of(BoundaryFaceData face) {
            return new FaceId(face.axis(), (long) face.planeCoord(), (long) face.minU());
        }
    }

    private record TimedFace(BoundaryFaceData face, long arrivalNano, long fadeOutNano) {
        static final long NOT_FADING = Long.MAX_VALUE;
        TimedFace(BoundaryFaceData face, long arrivalNano) { this(face, arrivalNano, NOT_FADING); }
        boolean isFading() { return fadeOutNano != NOT_FADING; }
    }

    // ========== State ==========
    private static volatile List<TimedFace> timedFaces = List.of();
    private static final HashMap<FaceId, Long> faceArrivalMap = new HashMap<>();
    private static double wallMinY, wallMaxY;

    private static float phaseAlpha = 0.0f;
    private static long visibleAfterNano = 0;
    private static long steadyEndNano = 0;
    private static long lastFrameNano = 0;

    // ========== Public API ==========

    public static void onRender(Vec3 cameraPos) {
        List<TimedFace> faces = timedFaces;
        boolean hasFaces = !faces.isEmpty();

        if (!hasFaces && phaseAlpha < 0.01f) return;

        long now = System.nanoTime();
        float dt = lastFrameNano > 0 ? (now - lastFrameNano) / 1_000_000_000f : 0.016f;
        dt = Math.min(dt, 0.1f);
        lastFrameNano = now;

        float targetAlpha;
        if (!hasFaces) {
            targetAlpha = 0.0f;
        } else if (now < visibleAfterNano) {
            targetAlpha = phaseAlpha > 0.05f ? 1.0f : 0.0f;
        } else if (now < steadyEndNano) {
            targetAlpha = 1.0f;
        } else {
            targetAlpha = 0.0f;
        }

        if (phaseAlpha < targetAlpha) {
            phaseAlpha = Math.min(targetAlpha, phaseAlpha + FADE_IN_RATE * dt);
        } else if (phaseAlpha > targetAlpha) {
            phaseAlpha = Math.max(targetAlpha, phaseAlpha - FADE_OUT_RATE * dt);
        }

        if (phaseAlpha < 0.01f) {
            phaseAlpha = 0.0f;
            if (now > visibleAfterNano && now > steadyEndNano) {
                timedFaces = List.of();
                lastFrameNano = 0;
            }
            return;
        }

        float breathe = 1.0f - BREATHE_AMP
                + BREATHE_AMP * (float) Math.sin(now / 1_000_000_000.0 * BREATHE_SPEED);
        float globalAlpha = BASE_ALPHA * phaseAlpha * breathe;

        float scroll = ((now / 1_000_000L) % 3000L) / 3000.0f;
        Matrix4f texMatrix = new Matrix4f().translation(scroll, scroll, 0);

        if (!faces.isEmpty()) {
            renderWalls(faces, now, globalAlpha, texMatrix, cameraPos);
        }
    }

    public static void updateBoundaries(SonarBoundaryPayload payload) {
        if (payload.foreignFaces().isEmpty()) {
            faceArrivalMap.clear();
            timedFaces = List.of();
            return;
        }

        long now = System.nanoTime();
        wallMinY = payload.wallMinY();
        wallMaxY = payload.wallMaxY();

        SonarType type = SonarType.fromId(payload.sonarType());
        long steadyDurationNs = (long) (type.wallSteadyDurationSeconds() * 1_000_000_000L);
        visibleAfterNano = now + SONAR_DELAY_NS;
        steadyEndNano = visibleAfterNano + steadyDurationNs;

        boolean freshActivation = timedFaces.isEmpty();
        long newFaceArrival = freshActivation ? now : visibleAfterNano;

        if (freshActivation) {
            faceArrivalMap.clear();
        }

        Set<FaceId> newIds = new HashSet<>();
        List<TimedFace> result = new ArrayList<>(payload.foreignFaces().size());
        for (BoundaryFaceData face : payload.foreignFaces()) {
            FaceId id = FaceId.of(face);
            newIds.add(id);
            long arrival = faceArrivalMap.computeIfAbsent(id, k -> newFaceArrival);
            result.add(new TimedFace(face, arrival));
        }

        if (!freshActivation) {
            for (TimedFace oldFace : timedFaces) {
                FaceId oldId = FaceId.of(oldFace.face());
                if (!newIds.contains(oldId) && !oldFace.isFading()) {
                    result.add(new TimedFace(oldFace.face(), oldFace.arrivalNano(), now));
                } else if (oldFace.isFading() && newIds.contains(FaceId.of(oldFace.face()))) {
                } else if (oldFace.isFading()) {
                    float fadeElapsed = (now - oldFace.fadeOutNano()) / 1_000_000_000f;
                    if (fadeElapsed < FACE_FADE_IN_S) {
                        result.add(oldFace);
                    }
                }
            }
        }
        faceArrivalMap.keySet().retainAll(newIds);
        timedFaces = List.copyOf(result);
    }

    public static void close() {
        ALLOCATOR.close();
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }

    // ========== Render ==========

    private static void renderWalls(List<TimedFace> faces, long now,
                                     float globalAlpha, Matrix4f texMatrix, Vec3 cam) {
        var pipeline = RenderPipelines.WORLD_BORDER;

        TreeMap<Integer, List<TimedFace>> buckets = new TreeMap<>();
        for (TimedFace tf : faces) {
            float faceAlpha;
            if (tf.isFading()) {
                float fadeElapsed = (now - tf.fadeOutNano()) / 1_000_000_000f;
                faceAlpha = Math.max(0.0f, 1.0f - fadeElapsed / FACE_FADE_IN_S);
            } else {
                float elapsed = (now - tf.arrivalNano()) / 1_000_000_000f;
                if (elapsed < 0.0f) continue;
                faceAlpha = Math.min(1.0f, elapsed / FACE_FADE_IN_S);
            }
            if (faceAlpha <= 0.0f) continue;
            int bucket = Math.max(1, Math.min(10, Math.round(faceAlpha * 10)));
            buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(tf);
        }
        if (buckets.isEmpty()) return;

        BufferBuilder builder = new BufferBuilder(ALLOCATOR,
                pipeline.getVertexFormatMode(), pipeline.getVertexFormat());

        List<int[]> bucketRanges = new ArrayList<>();
        int totalQuads = 0;

        for (var entry : buckets.entrySet()) {
            int bucketLevel = entry.getKey();
            int bucketStartQuad = totalQuads;

            for (TimedFace tf : entry.getValue()) {
                BoundaryFaceData face = tf.face();
                double plane = face.planeCoord();
                double minU = face.minU();
                double maxU = minU + 16.0;

                float texU1 = (float) (minU * 0.5);
                float texU2 = (float) (maxU * 0.5);
                float texV1 = (float) (wallMinY * 0.5);
                float texV2 = (float) (wallMaxY * 0.5);

                if (face.axis() == 0) {
                    addQuadYZ(builder, cam, plane, minU, wallMinY, maxU, wallMaxY,
                            texU1, texV1, texU2, texV2, true);
                    addQuadYZ(builder, cam, plane, minU, wallMinY, maxU, wallMaxY,
                            texU1, texV1, texU2, texV2, false);
                } else {
                    addQuadXY(builder, cam, plane, minU, wallMinY, maxU, wallMaxY,
                            texU1, texV1, texU2, texV2, true);
                    addQuadXY(builder, cam, plane, minU, wallMinY, maxU, wallMaxY,
                            texU1, texV1, texU2, texV2, false);
                }
                totalQuads += 2;
            }

            int quadsInBucket = totalQuads - bucketStartQuad;
            if (quadsInBucket > 0) {
                bucketRanges.add(new int[]{bucketLevel, bucketStartQuad, quadsInBucket});
            }
        }

        if (totalQuads == 0) return;

        drawBucketedWalls(builder, bucketRanges, totalQuads, globalAlpha, texMatrix);
    }

    private static void drawBucketedWalls(BufferBuilder builder, List<int[]> bucketRanges,
                                           int totalQuads, float globalAlpha,
                                           Matrix4f texMatrix) {
        var pipeline = RenderPipelines.WORLD_BORDER;

        MeshData built = builder.build();
        if (built == null) return;

        try {
            MeshData.DrawState drawState = built.drawState();
            VertexFormat format = drawState.format();
            int totalBytes = drawState.vertexCount() * format.getVertexSize();

            if (vertexBuffer == null || vertexBuffer.size() < totalBytes) {
                if (vertexBuffer != null) vertexBuffer.close();
                vertexBuffer = new MappableRingBuffer(
                        () -> "civil foreign wall",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE,
                        Math.max(totalBytes, 8192));
            }

            GpuBuffer gpuVerts = vertexBuffer.currentBuffer();
            var uploadEncoder = RenderSystem.getDevice().createCommandEncoder();
            try (GpuBuffer.MappedView mapped = uploadEncoder.mapBuffer(
                    gpuVerts.slice(0, built.vertexBuffer().remaining()), false, true)) {
                MemoryUtil.memCopy(built.vertexBuffer(), mapped.data());
            }

            int totalIndices = totalQuads * 6;
            RenderSystem.AutoStorageIndexBuffer shapeIdxBuf =
                    RenderSystem.getSequentialBuffer(pipeline.getVertexFormatMode());
            GpuBuffer indices = shapeIdxBuf.getBuffer(totalIndices);
            VertexFormat.IndexType indexType = shapeIdxBuf.type();

            Minecraft client = Minecraft.getInstance();
            AbstractTexture forcefield = client.getTextureManager().getTexture(FORCEFIELD_TEXTURE);
            var fb = client.getMainRenderTarget();

            for (int[] range : bucketRanges) {
                int bucketLevel = range[0];
                int firstQuad = range[1];
                int quadCount = range[2];

                float faceAlpha = bucketLevel / 10.0f;
                float finalAlpha = globalAlpha * faceAlpha;
                if (finalAlpha < 0.005f) continue;

                Vector4f color = new Vector4f(WALL_R, WALL_G, WALL_B, finalAlpha);

                GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                        .writeTransform(RenderSystem.getModelViewMatrix(), color, new Vector3f(), texMatrix);

                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                        .createRenderPass(
                                () -> "civil foreign wall",
                                fb.getColorTextureView(), OptionalInt.empty(),
                                fb.getDepthTextureView(), OptionalDouble.empty())) {
                    pass.setPipeline(pipeline);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", dynamicTransforms);
                    pass.bindTexture("Sampler0",
                            forcefield.getTextureView(), forcefield.getSampler());
                    pass.setVertexBuffer(0, gpuVerts);
                    pass.setIndexBuffer(indices, indexType);
                    pass.drawIndexed(0, firstQuad * 6, quadCount * 6, 1);
                }
            }

            vertexBuffer.rotate();
        } finally {
            built.close();
        }
    }

    // ========== Vertex emission ==========

    private static void addQuadYZ(BufferBuilder buf, Vec3 cam,
                                  double x, double minZ, double minY, double maxZ, double maxY,
                                  float u1, float v1, float u2, float v2,
                                  boolean front) {
        float fx = (float) (x - cam.x);
        float fy1 = (float) (minY - cam.y);
        float fy2 = (float) (maxY - cam.y);
        float fz1 = (float) (minZ - cam.z);
        float fz2 = (float) (maxZ - cam.z);

        if (front) {
            buf.addVertex(fx, fy1, fz1).setUv(u1, v1);
            buf.addVertex(fx, fy1, fz2).setUv(u2, v1);
            buf.addVertex(fx, fy2, fz2).setUv(u2, v2);
            buf.addVertex(fx, fy2, fz1).setUv(u1, v2);
        } else {
            buf.addVertex(fx, fy2, fz1).setUv(u1, v2);
            buf.addVertex(fx, fy2, fz2).setUv(u2, v2);
            buf.addVertex(fx, fy1, fz2).setUv(u2, v1);
            buf.addVertex(fx, fy1, fz1).setUv(u1, v1);
        }
    }

    private static void addQuadXY(BufferBuilder buf, Vec3 cam,
                                  double z, double minX, double minY, double maxX, double maxY,
                                  float u1, float v1, float u2, float v2,
                                  boolean front) {
        float fx1 = (float) (minX - cam.x);
        float fx2 = (float) (maxX - cam.x);
        float fy1 = (float) (minY - cam.y);
        float fy2 = (float) (maxY - cam.y);
        float fz = (float) (z - cam.z);

        if (front) {
            buf.addVertex(fx1, fy1, fz).setUv(u1, v1);
            buf.addVertex(fx2, fy1, fz).setUv(u2, v1);
            buf.addVertex(fx2, fy2, fz).setUv(u2, v2);
            buf.addVertex(fx1, fy2, fz).setUv(u1, v2);
        } else {
            buf.addVertex(fx1, fy2, fz).setUv(u1, v2);
            buf.addVertex(fx2, fy2, fz).setUv(u2, v2);
            buf.addVertex(fx2, fy1, fz).setUv(u2, v1);
            buf.addVertex(fx1, fy1, fz).setUv(u1, v1);
        }
    }
}
