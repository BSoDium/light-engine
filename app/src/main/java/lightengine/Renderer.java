package lightengine;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.io.File;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;

import lightengine.algebra.SizeMismatchException;
import lightengine.algebra.Vector;
import lightengine.algebra.Vector3;
import lightengine.tasks.Event;
import lightengine.tasks.TaskMgr;

/**
 * The Renderer class drives the rendering pipeline: read in a scene, projects
 * the vertices and rasterizes every faces / edges.
 * 
 * @author: cdehais
 */
public class Renderer {

    // camera settings
    static double fov = Math.PI / 2;
    static double near = 0.1;
    static double far = 100.0;
    static double aspect = 1;

    static Scene scene;
    static Mesh mesh;
    static GraphicsWrapper screen;
    static Transformation xform;
    static Lighting lighting;
    static TaskMgr taskMgr;
    static boolean lightingEnabled;

    static boolean isRunning;
    static int targetFrameRate = 5; // frames per second
    static float frameRate;
    static Color backgroundColor = new Color(24, 24, 33);
    static RenderingMode renderingMode = RenderingMode.WIREFRAME;

    static Shader[] shaders;
    static Rasterizer rasterizer;
    static int currentShader = 0;

    // time measurement
    public static int clock;

    static void init(String sceneFilename) throws Exception {
        taskMgr = new TaskMgr();
        scene = new Scene(sceneFilename);
        mesh = new Mesh(scene.getMeshFileName());
        screen = new GraphicsWrapper(taskMgr, scene.getScreenW(), scene.getScreenH());
        screen.clearBuffer();

        TextureShader texShader = new TextureShader(screen);
        texShader.setTexture("src/main/resources/textures/brick.jpg");

        shaders = new Shader[] { new SimpleShader(screen), new DepthShader(screen) };
        rasterizer = new PerspectiveCorrectRasterizer(shaders[currentShader]);

        xform = new Transformation();
        xform.setLookAt(scene.getCameraPosition(),
                scene.getCameraLookAt(),
                scene.getCameraUp());
        xform.setProjection(fov, aspect, near, far);
        xform.setCalibration(scene.getCameraFocal(), scene.getScreenW(), scene.getScreenH());

        lighting = new Lighting();
        lighting.addAmbientLight(scene.getAmbientI());
        double[] lightCoord = scene.getSourceCoord();
        lighting.addPointLight(lightCoord[0], lightCoord[1], lightCoord[2], scene.getSourceI());

        updateStatusText();
    }

    /**
     * Renders the elements in the scene.
     */
    static void render() {

        switch (renderingMode) {
            case WIREFRAME:
                renderWireframe();
                break;
            case SOLID:
                shaders[currentShader].reset();
                renderSolid();
                break;
            case SOLID_TEXTURE:
                renderSolid();
                break;
            default:
                throw new RuntimeException("Unknown rendering mode");
        }
    }

    static Fragment[] projectVertices() {
        Vector[] vertices = mesh.getVertices();
        Vector3[] normals = mesh.getNormals();
        double[] colors = mesh.getColors();

        Fragment[] fragments = new Fragment[vertices.length];

        try {
            for (int i = 0; i < vertices.length; i++) {
                Vector pVertex = xform.projectPoint(vertices[i]);
                // Vector pNormal = xform.transformVector (normals[i]);
                Vector3 pNormal = normals[i];

                int x = (int) Math.round(pVertex.get(0));
                int y = (int) Math.round(pVertex.get(1));
                fragments[i] = new Fragment(x, y);
                fragments[i].setDepth(pVertex.get(2));
                fragments[i].setNormal(pNormal);

                double[] texCoords = mesh.getTextureCoordinates();
                if (texCoords != null) {
                    fragments[i].setAttribute(7, texCoords[2 * i]);
                    fragments[i].setAttribute(8, texCoords[2 * i + 1]);
                }

                if (!lightingEnabled) {
                    fragments[i].setColor(colors[3 * i], colors[3 * i + 1], colors[3 * i + 2]);
                } else {
                    double[] color = new double[3];
                    color[0] = colors[3 * i];
                    color[1] = colors[3 * i + 1];
                    color[2] = colors[3 * i + 2];
                    double material[] = scene.getMaterial();
                    double[] litColor = lighting.applyLights(new Vector3(vertices[i]), pNormal, color,
                            scene.getCameraPosition(),
                            material[0], material[1], material[2], material[3]);
                    fragments[i].setColor(litColor[0], litColor[1], litColor[2]);
                }
            }
        } catch (SizeMismatchException | InstantiationException e) {
            e.printStackTrace();
            /* should not reach */
        }

        return fragments;
    }

    static void renderWireframe() {
        Fragment[] fragment = projectVertices();
        int[] faces = mesh.getFaces();

        for (int i = 0; i < 3 * mesh.getNumFaces(); i += 3) {
            for (int j = 0; j < 3; j++) {
                Fragment v1 = fragment[faces[i + j]];
                Fragment v2 = fragment[faces[i + ((j + 1) % 3)]];
                rasterizer.rasterizeEdge(v1, v2);
            }
        }
    }

    static void renderSolid() {
        Fragment[] fragments = projectVertices();
        int[] faces = mesh.getFaces();

        for (int i = 0; i < 3 * mesh.getNumFaces(); i += 3) {
            Fragment v1 = fragments[faces[i]];
            Fragment v2 = fragments[faces[i + 1]];
            Fragment v3 = fragments[faces[i + 2]];

            rasterizer.rasterizeFace(v1, v2, v3);
        }
    }

    public static void setLightingEnabled(boolean enabled) {
        lightingEnabled = enabled;
    }

    public static void wait(int sec) {
        try {
            Thread.sleep(sec * 1000);
        } catch (Exception e) {
            /* nothing */
        }
    }

    public static Transformation getXform() {
        return xform;
    }

    private static void updateStatusText() {
        String text = String.format(
                "Rendering mode: %s - Lighting %s - Shader: %s - FPS: %.2f",
                renderingMode,
                lightingEnabled ? "enabled" : "disabled", shaders[currentShader].getClass().getSimpleName(),
                frameRate);
        screen.setStatusText(text);
    }

    public static void main(String[] args) {

        try {
            // open file choosing dialog at current directory
            JFileChooser chooser = new JFileChooser();

            chooser.setCurrentDirectory(new File("src/main/resources/scenery"));
            chooser.setDialogTitle("Open scene file");
            chooser.setFileFilter(new FileNameExtensionFilter("Scene files", "scene"));
            chooser.setAcceptAllFileFilterUsed(false);
            int returnVal = chooser.showOpenDialog(null);
            if (returnVal == JFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                init(file.getAbsolutePath());
                isRunning = true;
            } else {
                System.out.println("No file selected.");
                return;
            }

        } catch (Exception e) {
            System.out.println("Problem initializing Renderer: " + e);
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        // add camera orbit task
        double camOrbitDist = scene.getCameraPosition().distance(scene.getCameraLookAt());
        taskMgr.addTask(Event.NEW_FRAME, payload -> {
            // determine next camera pos (time-based)
            double t = clock / 50.0;
            Vector3 cameraPos = xform.getCameraPosition();
            cameraPos = new Vector3(camOrbitDist * Math.sin(t), cameraPos.get(1),
                    camOrbitDist * Math.cos(t));
            xform.setLookAt(cameraPos, xform.getCameraLookAt(), xform.getCameraUp());

            // update status text
            if (clock % 60 == 0) {
                updateStatusText();
            }
        });

        // add window resize task
        taskMgr.addTask(Event.WINDOW_RESIZED, payload -> {
            int[] size = payload.getIntArray();
            xform.setCalibration(scene.getCameraFocal(), size[0], size[1]);
            screen.resize(size[0], size[1]);
            for (Shader s : shaders) {
                s.reset();
            }
            updateStatusText();
        });

        // add camera displacement task
        taskMgr.addTask(Event.KEY_PRESSED, payload -> {
            if (payload.getKeyCode() == KeyEvent.VK_UP) {
                Vector3 cameraPos = xform.getCameraPosition();
                cameraPos.set(cameraPos.get(0), cameraPos.get(1) + 0.3, cameraPos.get(2));
                xform.setLookAt(cameraPos, xform.getCameraLookAt(), xform.getCameraUp());
            } else if (payload.getKeyCode() == KeyEvent.VK_DOWN) {
                Vector3 cameraPos = xform.getCameraPosition();
                cameraPos.set(cameraPos.get(0), cameraPos.get(1) - 0.3, cameraPos.get(2));
                xform.setLookAt(cameraPos, xform.getCameraLookAt(), xform.getCameraUp());
            }
        });

        // add rendering mode switching task
        taskMgr.addTask(Event.KEY_PRESSED, payload -> {
            switch (payload.getKeyCode()) {
                case KeyEvent.VK_F1:
                    renderingMode = RenderingMode.WIREFRAME;
                    break;
                case KeyEvent.VK_F2:
                    renderingMode = RenderingMode.SOLID;
                    break;
                case KeyEvent.VK_F3:
                    renderingMode = RenderingMode.SOLID_TEXTURE;
                    break;
                default:
                    break;
            }
            updateStatusText();
        });

        // add lighting toggle task and exit task
        taskMgr.addTask(Event.KEY_PRESSED, payload -> {
            if (payload.getKeyCode() == KeyEvent.VK_L) {
                setLightingEnabled(!lightingEnabled);
                updateStatusText();
            } else if (payload.getKeyCode() == KeyEvent.VK_ESCAPE) {
                isRunning = false;
            }
        });

        // add shader switching task
        taskMgr.addTask(Event.KEY_PRESSED, payload -> {
            if (payload.getKeyCode() == KeyEvent.VK_SPACE) {
                currentShader = (currentShader + 1) % shaders.length;
                rasterizer.setShader(shaders[currentShader]);
                updateStatusText();
            }
        });

        // Main loop
        long frameTime;
        long renderTime;
        while (isRunning) {

            frameTime = System.currentTimeMillis();

            try {
                screen.clearBuffer(backgroundColor);
                render();
                screen.swapBuffers();
                taskMgr.triggerTasks(Event.NEW_FRAME);
            } catch (ArrayIndexOutOfBoundsException e) {
                System.out.println("Frame skipped: encountered ArrayIndexOutOfBoundsException.");
            }

            renderTime = System.currentTimeMillis() - frameTime;

            // update clock
            clock++;

            // cap frame rate
            try {
                Thread.sleep(Math.max(0, (int) (1000 / targetFrameRate - renderTime)));
            } catch (InterruptedException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }

            frameTime = System.currentTimeMillis() - frameTime;
            frameRate = (float) 1000. / frameTime;
        }

        screen.destroy();
        System.exit(0);
    }
}