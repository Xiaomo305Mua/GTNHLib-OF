package com.gtnewhorizon.gtnhlib.client.renderer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.renderer.Tessellator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.github.bsideup.jabel.Desugar;
import com.gtnewhorizon.gtnhlib.client.renderer.cel.model.line.ModelLine;
import com.gtnewhorizon.gtnhlib.client.renderer.cel.model.primitive.ModelPrimitiveView;
import com.gtnewhorizon.gtnhlib.client.renderer.cel.model.quad.ModelQuadViewMutable;
import com.gtnewhorizon.gtnhlib.client.renderer.cel.model.tri.ModelTriangle;
import com.gtnewhorizon.gtnhlib.client.renderer.tessellator.TessellatorCallback;
import com.gtnewhorizon.gtnhlib.client.renderer.vao.IVertexArrayObject;
import com.gtnewhorizon.gtnhlib.client.renderer.vao.VAOManager;
import com.gtnewhorizon.gtnhlib.client.renderer.vao.VertexBufferType;
import com.gtnewhorizon.gtnhlib.client.renderer.vbo.VertexBuffer;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormat;

@SuppressWarnings("unused")
public class TessellatorManager {

    public static final Logger LOGGER = LogManager.getLogger("TessellatorManager");

    private enum CaptureMode {
        CAPTURING,
        COMPILING
    }

    private static class CaptureState {

        final CaptureMode mode;
        final DrawCallback callback;
        List<ModelQuadViewMutable> savedQuads;

        CaptureState(CaptureMode mode, DrawCallback callback) {
            this.mode = mode;
            this.callback = callback;
        }
    }

    @Desugar
    public record CapturedGeometry(List<VertexBuffer> vbos) {

        public boolean isEmpty() {
            return vbos.isEmpty();
        }

        public void delete() {
            for (int i = 0, size = vbos.size(); i < size; i++) {
                vbos.get(i).delete();
            }
        }

        public void renderAll() {
            for (int i = 0, size = vbos.size(); i < size; i++) {
                vbos.get(i).render();
            }
        }
    }

    private static final ThreadLocal<CapturingTessellator> capturingTessellator = ThreadLocal
            .withInitial(CapturingTessellator::new);
    private static final ThreadLocal<LocalTessellator> localTessellator = ThreadLocal
            .withInitial(LocalTessellator::new);

    private static final ThreadLocal<ArrayList<CaptureState>> captureStack = ThreadLocal.withInitial(ArrayList::new);
    private static final Thread mainThread = Thread.currentThread();

    @Deprecated
    private static boolean isInCompilingCallback = false;

    public static Tessellator get() {
        final LocalTessellator local = localTessellator.get();
        if (local.active) {
            return local;
        }
        final ArrayList<CaptureState> stack = captureStack.get();
        if (!stack.isEmpty()) {
            return capturingTessellator.get();
        } else if (isOnMainThread()) {
            if (hasDirectTessellator()) return getDirectTessellator();
            return Tessellator.instance;
        } else {
            throw new IllegalStateException("Tried to get the Tessellator off the main thread when not capturing!");
        }
    }

    public static LocalTessellator getLocal() {
        return localTessellator.get();
    }

    public static LocalTessellator enterLocalMode() {
        final LocalTessellator local = localTessellator.get();
        local.active = true;
        return local;
    }

    public static void exitLocalMode() {
        final LocalTessellator local = localTessellator.get();
        local.active = false;
        local.discard();
    }

    public static boolean isCurrentlyCapturing() {
        CaptureState current = peekState();
        return current != null && current.mode == CaptureMode.CAPTURING;
    }

    static boolean isCurrentlyCompiling() {
        CaptureState current = peekState();
        return current != null && current.mode == CaptureMode.COMPILING;
    }

    public static boolean isOnMainThread() {
        return Thread.currentThread() == mainThread;
    }

    public static boolean isMainInstance(Object instance) {
        return instance == Tessellator.instance || isOnMainThread();
    }

    private static CaptureState peekState() {
        ArrayList<CaptureState> stack = captureStack.get();
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    private static CaptureState requireMode(CaptureMode expected, String errorMsg) {
        ArrayList<CaptureState> stack = captureStack.get();
        if (stack.isEmpty() || stack.get(stack.size() - 1).mode != expected) {
            throw new IllegalStateException(errorMsg);
        }
        return stack.get(stack.size() - 1);
    }

    @Deprecated
    private static void setVanillaTessellatorCompiling(boolean compiling) {
        if (Tessellator.instance instanceof ITessellatorInstance tessInst) {
            tessInst.gtnhlib$setCompiling(compiling);
        }
    }

    private static boolean hasCompilingInStack(ArrayList<CaptureState> stack) {
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i).mode == CaptureMode.COMPILING) {
                return true;
            }
        }
        return false;
    }

    private static void saveParentQuadsIfNeeded(ArrayList<CaptureState> stack, CapturingTessellator tess) {
        if (stack.isEmpty()) {
            return;
        }

        CaptureState parent = stack.get(stack.size() - 1);
        if (parent.mode == CaptureMode.CAPTURING) {
            parent.savedQuads = new ArrayList<>(tess.getQuads());
            tess.getQuads().clear();
        }
    }

    public static void startCapturing() {
        startCapturingAndGet();
    }

    public static CapturingTessellator startCapturingAndGet() {
        ArrayList<CaptureState> stack = captureStack.get();
        final CapturingTessellator tess = capturingTessellator.get();

        saveParentQuadsIfNeeded(stack, tess);

        if (!tess.getQuads().isEmpty()) {
            throw new IllegalStateException("Tried to start capturing with existing collected Quads!");
        }

        tess.storeTranslation();
        stack.add(new CaptureState(CaptureMode.CAPTURING, null));

        return tess;
    }

    public static List<ModelQuadViewMutable> stopCapturingToPooledQuads() {
        CaptureState currentState = requireMode(CaptureMode.CAPTURING, "Tried to stop capturing when not capturing!");
        ArrayList<CaptureState> stack = captureStack.get();

        final CapturingTessellator tess = capturingTessellator.get();

        if (tess.isDrawing) tess.draw();

        stack.remove(stack.size() - 1);
        tess.restoreTranslation();

        boolean isNested = !stack.isEmpty() && stack.get(stack.size() - 1).mode == CaptureMode.CAPTURING;

        List<ModelQuadViewMutable> quads;

        if (currentState.savedQuads != null) {
            quads = currentState.savedQuads;
            quads.addAll(tess.getQuads());
            tess.getQuads().clear();
        } else if (isNested) {
            quads = new ArrayList<>(tess.getQuads());
            tess.getQuads().clear();
        } else {
            quads = tess.getQuads();
        }

        return quads;
    }

    public static ByteBuffer stopCapturingToBuffer(VertexFormat format) {
        final ByteBuffer buf = CapturingTessellator.quadsToBuffer(stopCapturingToPooledQuads(), format);
        capturingTessellator.get().clearQuads();
        return buf;
    }

    @Deprecated
    public static VertexBuffer stopCapturingToVBO(VertexFormat format) {
        return new VertexBuffer(format, GL11.GL_QUADS).upload(stopCapturingToBuffer(format));
    }

    public static final int DEFAULT_BUFFER_SIZE = 0x8000;
    private static final int BUFFER_CAPACITY = DEFAULT_BUFFER_SIZE;
    public static final int DIRECT_TESSELLATOR_STACK_DEPTH = 16;

    private static final DirectTessellator mainInstance;
    private static final CallbackTessellator mainCallbackInstance;

    static {
        ByteBuffer sharedBuffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
        mainInstance = new DirectTessellator(sharedBuffer);
        mainCallbackInstance = new CallbackTessellator(sharedBuffer);
    }

    private static boolean mainInstanceInStack = false;

    private static final DirectTessellator[] directTessellators = new DirectTessellator[DIRECT_TESSELLATOR_STACK_DEPTH];
    private static int directTessellatorIndex = -1;

    private static DirectTessellator getDirectTessellator() {
        return directTessellators[directTessellatorIndex];
    }

    private static boolean hasDirectTessellator() {
        return directTessellatorIndex != -1;
    }

    public static int getDirectCaptureDepth() {
        return directTessellatorIndex + 1;
    }

    private static void setDirectTessellator(DirectTessellator tessellator) {
        if (++directTessellatorIndex >= DIRECT_TESSELLATOR_STACK_DEPTH) {
            directTessellatorIndex--;
            throw new IllegalStateException("DirectTessellator stack overflow");
        }
        mainInstanceInStack = mainInstanceInStack || tessellator == mainInstance;
        directTessellators[directTessellatorIndex] = tessellator;
    }

    public static DirectTessellator startCapturingDirect() {
        if (!mainInstanceInStack) {
            setDirectTessellator(mainInstance);
            return mainInstance;
        }
        final DirectTessellator tessellator = new DirectTessellator(DEFAULT_BUFFER_SIZE);
        setDirectTessellator(tessellator);
        return tessellator;
    }

    public static DirectTessellator startCapturingDirect(int capacity) {
        if (!mainInstanceInStack && BUFFER_CAPACITY >= capacity) {
            setDirectTessellator(mainInstance);
            return mainInstance;
        }
        final DirectTessellator tessellator = new DirectTessellator(capacity);
        setDirectTessellator(tessellator);
        return tessellator;
    }

    public static DirectTessellator startCapturingDirect(VertexFormat format) {
        final DirectTessellator tessellator = startCapturingDirect();
        try {
            tessellator.setVertexFormat(format);
        } catch (Throwable t) {
            stopCapturingDirect();
            throw t;
        }
        return tessellator;
    }

    @Deprecated
    public static CallbackTessellator startCapturingDirect(DirectDrawCallback callback) {
        return startCapturingDirect(new TessellatorCallback() {

            @Override
            public boolean onDraw(CallbackTessellator tessellator) {
                return callback.onDraw(tessellator);
            }
        });
    }

    public static CallbackTessellator startCapturingDirect(TessellatorCallback callback) {
        final CallbackTessellator tessellator = mainInstanceInStack ? new CallbackTessellator(DEFAULT_BUFFER_SIZE)
                : mainCallbackInstance;
        VertexCallbackManager.pushCallback(tessellator, callback);
        setDirectTessellator(tessellator);
        return tessellator;
    }

    public static void startCapturingDirect(DirectTessellator tessellator) {
        setDirectTessellator(tessellator);
    }

    public static void stopCapturingDirect() {
        if (!hasDirectTessellator()) throw new IllegalStateException("Tried to stop capturing when not capturing!");
        final DirectTessellator tessellator = getDirectTessellator();
        directTessellators[directTessellatorIndex--] = null;
        mainInstanceInStack = mainInstanceInStack && tessellator != mainInstance;
        tessellator.onRemovedFromStack();
    }

    public static IVertexArrayObject stopCapturingDirectToVBO(VertexBufferType bufferType) {
        if (!hasDirectTessellator()) throw new IllegalStateException("Tried to stop capturing when not capturing!");
        final DirectTessellator tessellator = getDirectTessellator();
        final IVertexArrayObject vbo = tessellator.uploadToVBO(bufferType);
        stopCapturingDirect();
        return vbo;
    }

    @Deprecated
    public static CapturedGeometry stopCapturingToGeometry(VertexFormat format) {
        CaptureState currentState = requireMode(CaptureMode.CAPTURING, "Tried to stop capturing when not capturing!");
        ArrayList<CaptureState> stack = captureStack.get();
        final CapturingTessellator tess = capturingTessellator.get();

        if (tess.isDrawing) tess.draw();

        stack.remove(stack.size() - 1);

        tess.restoreTranslation();

        List<ModelLine> lines = tess.lineListCache;
        List<ModelTriangle> triangles = tess.triangleListCache;
        List<ModelQuadViewMutable> quads = tess.quadListCache;
        lines.clear();
        triangles.clear();
        quads.clear();

        List<ModelPrimitiveView> prims = tess.getPrimitives();
        for (int i = 0, size = prims.size(); i < size; i++) {
            ModelPrimitiveView prim = prims.get(i);
            if (prim instanceof ModelLine ml) {
                lines.add(ml);
            } else if (prim instanceof ModelTriangle mt) {
                triangles.add(mt);
            }
        }

        List<ModelQuadViewMutable> collectedQuads = tess.getQuads();
        for (int i = 0, size = collectedQuads.size(); i < size; i++) {
            ModelQuadViewMutable quad = collectedQuads.get(i);
            quads.add(quad);
        }

        List<VertexBuffer> vbos = new ArrayList<>();
        if (!lines.isEmpty()) vbos.add(createLineVBO(lines, format));
        if (!triangles.isEmpty()) vbos.add(createTriangleVBO(triangles, format));
        if (!quads.isEmpty()) vbos.add(createQuadVBO(quads, format));

        tess.clearPrimitives();
        tess.clearQuads();

        if (!stack.isEmpty() && stack.get(stack.size() - 1).mode == CaptureMode.CAPTURING) {
        } else {
            tess.discard();
        }

        return new CapturedGeometry(vbos);
    }

    @Deprecated
    private static VertexBuffer createLineVBO(List<ModelLine> lines, VertexFormat format) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(format.getVertexSize() * lines.size() * 2);
        for (int i = 0, size = lines.size(); i < size; i++) {
            writePrimitiveToBuffer(lines.get(i), buffer, format);
        }
        buffer.flip();
        return new VertexBuffer(format, GL11.GL_LINES).upload(buffer);
    }

    @Deprecated
    private static VertexBuffer createTriangleVBO(List<ModelTriangle> triangles, VertexFormat format) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(format.getVertexSize() * triangles.size() * 3);
        for (int i = 0, size = triangles.size(); i < size; i++) {
            writePrimitiveToBuffer(triangles.get(i), buffer, format);
        }
        buffer.flip();
        return new VertexBuffer(format, GL11.GL_TRIANGLES).upload(buffer);
    }

    @Deprecated
    private static VertexBuffer createQuadVBO(List<ModelQuadViewMutable> quads, VertexFormat format) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(format.getVertexSize() * quads.size() * 4);
        format.writeQuads(quads, buffer);
        buffer.flip();
        return new VertexBuffer(format, GL11.GL_QUADS).upload(buffer);
    }

    private static final int EXPECTED_PRIMITIVE_VERTEX_SIZE = 32;

    private static void writePrimitiveToBuffer(ModelPrimitiveView prim, ByteBuffer buffer, VertexFormat format) {
        if (format.getVertexSize() != EXPECTED_PRIMITIVE_VERTEX_SIZE) {
            throw new IllegalArgumentException(
                    "writePrimitiveToBuffer requires vertex size of " + EXPECTED_PRIMITIVE_VERTEX_SIZE
                            + " bytes, but format has "
                            + format.getVertexSize()
                            + ". Use a compatible format or implement a custom primitive writer.");
        }

        for (int i = 0; i < prim.getVertexCount(); i++) {
            buffer.putFloat(prim.getX(i));
            buffer.putFloat(prim.getY(i));
            buffer.putFloat(prim.getZ(i));

            buffer.putInt(prim.getColor(i));

            buffer.putFloat(prim.getTexU(i));
            buffer.putFloat(prim.getTexV(i));

            buffer.putInt(prim.getLight(i));

            buffer.putInt(prim.getForgeNormal(i));
        }
    }

    @Deprecated
    public static VertexBuffer stopCapturingToVBO(VertexBuffer vbo, VertexFormat format) {
        if (vbo == null) {
            vbo = new VertexBuffer(format, GL11.GL_QUADS);
        }
        return vbo.upload(stopCapturingToBuffer(format));
    }

    @Deprecated
    public static VertexBuffer stopCapturingToVAO(VertexFormat format) {
        return VAOManager.createVAO(format, GL11.GL_QUADS).upload(stopCapturingToBuffer(format));
    }

    @Deprecated
    public static VertexBuffer stopCapturingToVAO(VertexBuffer vao, VertexFormat format) {
        if (vao == null) {
            vao = VAOManager.createVAO(format, GL11.GL_QUADS);
        }
        return vao.upload(stopCapturingToBuffer(format));
    }

    public static boolean shouldInterceptDraw(Tessellator tess) {
        return ((ITessellatorInstance) tess).gtnhlib$isCompiling()
                || (hasDirectTessellator() && !isCurrentlyCapturing());
    }

    public static int interceptDraw(Tessellator tess) {
        if (hasDirectTessellator()) {
            final DirectTessellator tessellator = getDirectTessellator();
            final int result = tessellator.interceptDraw(tess);

            discardTessellator(tess);
            return result;
        }

        if (isInCompilingCallback) {
            throw new IllegalStateException(
                    "Tessellator.draw() called from within a compiling callback - this is not allowed!");
        }

        CaptureState current = requireMode(CaptureMode.COMPILING, "interceptDraw called but not in COMPILING mode!");
        if (current.callback == null) {
            throw new IllegalStateException("interceptDraw called but callback is null!");
        }

        final CapturingTessellator helper = capturingTessellator.get();
        if (tess.drawMode == GL11.GL_QUADS) {
            QuadExtractor.buildQuadsFromBuffer(
                    tess.rawBuffer,
                    tess.vertexCount,
                    tess.drawMode,
                    true,
                    tess.hasBrightness,
                    tess.hasColor,
                    tess.hasNormals,
                    0,
                    0,
                    0,
                    -1,
                    helper.quadPool,
                    helper.collectedQuads,
                    helper.flags);
        } else {
            PrimitiveExtractor.buildPrimitivesFromBuffer(
                    tess.rawBuffer,
                    tess.vertexCount,
                    tess.drawMode,
                    true,
                    tess.hasBrightness,
                    tess.hasColor,
                    tess.hasNormals,
                    0,
                    0,
                    0,
                    -1,
                    helper.quadPool,
                    helper.triPool,
                    helper.linePool,
                    helper.collectedPrimitives,
                    helper.flags);
        }

        isInCompilingCallback = true;
        try {
            current.callback.onDraw(helper.collectedQuads, helper.collectedPrimitives, helper.flags);
        } finally {
            isInCompilingCallback = false;
        }
        helper.clearQuads();
        helper.clearPrimitives();

        int result = tess.rawBufferIndex * 4;
        ((ITessellatorInstance) tess).discard();
        return result;
    }

    static int processDrawForCapturingTessellator(CapturingTessellator tess) {
        final CaptureState current = peekState();
        final boolean isCompiling = current != null && current.mode == CaptureMode.COMPILING;

        if (isCompiling) {
            if (tess.drawMode == GL11.GL_QUADS) {
                QuadExtractor.buildQuadsFromBuffer(
                        tess.rawBuffer,
                        tess.vertexCount,
                        tess.drawMode,
                        tess.hasTexture,
                        tess.hasBrightness,
                        tess.hasColor,
                        tess.hasNormals,
                        -tess.offset.x,
                        -tess.offset.y,
                        -tess.offset.z,
                        tess.shaderBlockId,
                        tess.quadPool,
                        tess.collectedQuads,
                        tess.flags);
            } else {
                PrimitiveExtractor.buildPrimitivesFromBuffer(
                        tess.rawBuffer,
                        tess.vertexCount,
                        tess.drawMode,
                        tess.hasTexture,
                        tess.hasBrightness,
                        tess.hasColor,
                        tess.hasNormals,
                        -tess.offset.x,
                        -tess.offset.y,
                        -tess.offset.z,
                        tess.shaderBlockId,
                        tess.quadPool,
                        tess.triPool,
                        tess.linePool,
                        tess.collectedPrimitives,
                        tess.flags);
            }
            current.callback.onDraw(tess.collectedQuads, tess.collectedPrimitives, tess.flags);
            tess.clearQuads();
            tess.clearPrimitives();
        } else {
            if (tess.drawMode == GL11.GL_QUADS || tess.drawMode == GL11.GL_TRIANGLES) {
                QuadExtractor.buildQuadsFromBuffer(
                        tess.rawBuffer,
                        tess.vertexCount,
                        tess.drawMode,
                        tess.hasTexture,
                        tess.hasBrightness,
                        tess.hasColor,
                        tess.hasNormals,
                        -tess.offset.x,
                        -tess.offset.y,
                        -tess.offset.z,
                        tess.shaderBlockId,
                        tess.quadPool,
                        tess.collectedQuads,
                        tess.flags);
            } else {
                PrimitiveExtractor.buildPrimitivesFromBuffer(
                        tess.rawBuffer,
                        tess.vertexCount,
                        tess.drawMode,
                        tess.hasTexture,
                        tess.hasBrightness,
                        tess.hasColor,
                        tess.hasNormals,
                        -tess.offset.x,
                        -tess.offset.y,
                        -tess.offset.z,
                        tess.shaderBlockId,
                        tess.quadPool,
                        tess.triPool,
                        tess.linePool,
                        tess.collectedPrimitives,
                        tess.flags);
            }
        }

        final int result = tess.rawBufferIndex * 4;
        tess.discard();
        return result;
    }

    @Deprecated
    public static void cleanup() {
        final CapturingTessellator tessellator = capturingTessellator.get();
        final ArrayList<CaptureState> stack = captureStack.get();

        if (isOnMainThread()) {
            final CaptureState current = peekState();
            if (current != null && current.mode == CaptureMode.COMPILING) {
                LOGGER.warn(
                        "[TessellatorManager] cleanup() called while compiling is active - this may indicate cleanup() called during display list compilation!",
                        new Exception("Stack trace"));
                setVanillaTessellatorCompiling(false);
            }
        }

        stack.clear();
        tessellator.discard();
        tessellator.clearQuads();
        tessellator.clearPrimitives();
        isInCompilingCallback = false;
    }

    @Deprecated
    public static void setCompiling(DrawCallback callback) {
        if (callback == null) throw new IllegalArgumentException("Callback cannot be null");
        if (!isOnMainThread()) {
            throw new IllegalStateException("Display list compilation can only happen on main thread!");
        }

        ArrayList<CaptureState> stack = captureStack.get();

        final CapturingTessellator tess = capturingTessellator.get();
        if (!tess.getQuads().isEmpty()) {
            throw new IllegalStateException("Tried to start compiling with existing collected Quads!");
        }

        stack.add(new CaptureState(CaptureMode.COMPILING, callback));

        setVanillaTessellatorCompiling(true);
        tess.storeTranslation();
    }

    @Deprecated
    public static void stopCompiling() {
        if (!isOnMainThread()) {
            throw new IllegalStateException("stopCompiling() can only be called from main thread!");
        }

        requireMode(CaptureMode.COMPILING, "Not currently compiling!");
        ArrayList<CaptureState> stack = captureStack.get();

        final CapturingTessellator tess = capturingTessellator.get();

        if (tess.isDrawing) {
            tess.draw();
        }

        stack.remove(stack.size() - 1);

        if (!hasCompilingInStack(stack)) {
            setVanillaTessellatorCompiling(false);
        }

        tess.clearQuads();
        tess.discard();
        tess.restoreTranslation();
    }

    public static void discardTessellator(Tessellator tessellator) {
        tessellator.reset();
        tessellator.isDrawing = false;
        tessellator.hasNormals = false;
        tessellator.hasColor = false;
        tessellator.hasTexture = false;
        tessellator.hasBrightness = false;
        tessellator.isColorDisabled = false;
    }

    public static Tessellator getVanillaTessellator() {
        return Tessellator.instance;
    }

    public static Tessellator getMainThreadTessellator() {
        if (hasDirectTessellator()) return getDirectTessellator();
        return Tessellator.instance;
    }
}
