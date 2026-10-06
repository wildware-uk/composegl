import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33;

/** GpuBurn <seconds> <loop>: keeps the GPU busy with a heavy full-screen shader, as a running game or emulator would. */
public class GpuBurn {
    public static void main(String[] args) {
        long end = System.nanoTime() + Long.parseLong(args[0]) * 1_000_000_000L;
        int loop = Integer.parseInt(args[1]);
        GLFW.glfwInit();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long w = GLFW.glfwCreateWindow(1920, 1080, "burn", 0, 0);
        GLFW.glfwMakeContextCurrent(w);
        GLFW.glfwSwapInterval(0);
        GL.createCapabilities();
        System.out.println("renderer " + GL33.glGetString(GL33.GL_RENDERER));
        int vs = GL33.glCreateShader(GL33.GL_VERTEX_SHADER);
        GL33.glShaderSource(vs, "#version 330\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}");
        GL33.glCompileShader(vs);
        int fs = GL33.glCreateShader(GL33.GL_FRAGMENT_SHADER);
        GL33.glShaderSource(fs, "#version 330\nuniform int n;uniform float t;out vec4 c;void main(){vec2 p=gl_FragCoord.xy*0.001;float a=t;for(int i=0;i<n;i++){a=sin(a+p.x)*cos(a-p.y)+0.001*float(i);}c=vec4(a,a,a,1);}");
        GL33.glCompileShader(fs);
        int pr = GL33.glCreateProgram();
        GL33.glAttachShader(pr, vs); GL33.glAttachShader(pr, fs); GL33.glLinkProgram(pr);
        GL33.glUseProgram(pr);
        int vao = GL33.glGenVertexArrays(); GL33.glBindVertexArray(vao);
        GL33.glUniform1i(GL33.glGetUniformLocation(pr, "n"), loop);
        int tl = GL33.glGetUniformLocation(pr, "t");
        long frames = 0;
        while (System.nanoTime() < end) {
            GL33.glUniform1f(tl, frames * 0.01f);
            GL33.glDrawArrays(GL33.GL_TRIANGLES, 0, 3);
            GLFW.glfwSwapBuffers(w);
            frames++;
        }
        System.out.println("frames " + frames);
    }
}
