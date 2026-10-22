// Non-interactive pipe transport, adapted from terminal/src/main/cpp/termux_pty.cpp.
// Keeps the existing Termux terminal JNI ABI unchanged.
#include <jni.h>
#include <cerrno>
#include <climits>
#include <fcntl.h>
#include <sys/wait.h>
#include <unistd.h>
#include <string>
#include <vector>

static std::vector<std::string> strings(JNIEnv* env, jobjectArray values) {
    std::vector<std::string> result;
    for (jsize i = 0; i < env->GetArrayLength(values); ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        const char* text = env->GetStringUTFChars(value, nullptr);
        result.emplace_back(text);
        env->ReleaseStringUTFChars(value, text);
        env->DeleteLocalRef(value);
    }
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_me_rerere_workspace_NativeWorkspaceProcessJni_start(JNIEnv* env, jobject, jobjectArray command, jstring directory, jobjectArray environment) {
    auto args = strings(env, command);
    auto vars = strings(env, environment);
    auto cwdChars = env->GetStringUTFChars(directory, nullptr);
    std::string cwd(cwdChars);
    env->ReleaseStringUTFChars(directory, cwdChars);
    std::vector<char*> argv, envp;
    for (auto& value : args) argv.push_back(value.data());
    for (auto& value : vars) envp.push_back(value.data());
    argv.push_back(nullptr); envp.push_back(nullptr);
    int pipes[4][2] = {{-1,-1},{-1,-1},{-1,-1},{-1,-1}};
    auto closePipes = [&]() { for (auto& pair : pipes) for (int fd : pair) if (fd >= 0) close(fd); };
    for (auto& pair : pipes) {
        if (pipe2(pair, O_CLOEXEC) != 0) {
            closePipes();
            env->ThrowNew(env->FindClass("java/io/IOException"), "Cannot create command pipes");
            return nullptr;
        }
    }
    pid_t pid = fork();
    if (pid == 0) {
        close(pipes[3][0]);
        if (setsid() < 0 || chdir(cwd.c_str()) < 0 || dup2(pipes[0][0], 0) < 0 || dup2(pipes[1][1], 1) < 0 || dup2(pipes[2][1], 2) < 0) {
            int error = errno; write(pipes[3][1], &error, sizeof(error)); _exit(127);
        }
        for (int i = 0; i < 3; ++i) for (int fd : pipes[i]) close(fd);
        execve(argv[0], argv.data(), envp.data());
        int error = errno; write(pipes[3][1], &error, sizeof(error)); _exit(127);
    }
    if (pid < 0) {
        closePipes();
        env->ThrowNew(env->FindClass("java/io/IOException"), "Cannot fork command");
        return nullptr;
    }
    close(pipes[0][0]); close(pipes[1][1]); close(pipes[2][1]); close(pipes[3][1]);
    int error = 0;
    ssize_t size;
    do { size = read(pipes[3][0], &error, sizeof(error)); } while (size < 0 && errno == EINTR);
    close(pipes[3][0]);
    if (size != 0) {
        close(pipes[0][1]); close(pipes[1][0]); close(pipes[2][0]);
        int status; while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
        env->ThrowNew(env->FindClass("java/io/IOException"), "Cannot execute workspace command");
        return nullptr;
    }
    jint values[] = {pid, pipes[0][1], pipes[1][0], pipes[2][0]};
    auto result = env->NewIntArray(4);
    env->SetIntArrayRegion(result, 0, 4, values);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_workspace_NativeWorkspaceProcessJni_poll(JNIEnv*, jobject, jint pid) {
    siginfo_t status = {};
    int result;
    // Keep the PID reserved until close(), so descendant cleanup cannot signal a reused PID.
    do { result = waitid(P_PID, pid, &status, WEXITED | WNOHANG | WNOWAIT); } while (result < 0 && errno == EINTR);
    if (result < 0) return -1;
    if (status.si_pid == 0) return INT_MIN;
    return status.si_code == CLD_EXITED ? status.si_status : 128 + status.si_status;
}

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_workspace_NativeWorkspaceProcessJni_reap(JNIEnv*, jobject, jint pid) {
    int status; while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
}
