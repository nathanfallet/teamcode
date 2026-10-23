#ifndef TEAMCODE_PROCESS_H
#define TEAMCODE_PROCESS_H
#include <spawn.h>
#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>
#include <errno.h>
#include <stdint.h>
#include <fcntl.h>
#include <poll.h>
#include <time.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>

/* Hook commands receive a pipe, never interactive input. Bound both input and wait. */
static inline int teamcode_hook_input(unsigned char *buffer, int capacity) {
    if (isatty(STDIN_FILENO)) return -1;
    struct timespec start, now;
    if (clock_gettime(CLOCK_MONOTONIC, &start) != 0) return -1;
    int total = 0;
    for (;;) {
        if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -1;
        long elapsed = (now.tv_sec - start.tv_sec) * 1000 + (now.tv_nsec - start.tv_nsec) / 1000000;
        if (elapsed >= 1000) return -1;
        struct pollfd input = {STDIN_FILENO, POLLIN, 0};
        int ready = poll(&input, 1, (int)(1000 - elapsed));
        if (ready < 0 && errno == EINTR) continue;
        if (ready <= 0 || (input.revents & (POLLERR | POLLNVAL))) return -1;
        ssize_t count = read(STDIN_FILENO, buffer + total, (size_t)(capacity - total));
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) return -1;
        if (count == 0) return total;
        total += (int)count;
        if (total >= capacity) return total; /* Full buffer: the caller treats more than 64 KiB as oversized. */
    }
}
#ifdef __APPLE__
#include <crt_externs.h>
#include <mach-o/dyld.h>
#endif

static inline int teamcode_executable(char *buffer, uint32_t capacity) {
#ifdef __APPLE__
    return _NSGetExecutablePath(buffer, &capacity);
#else
    ssize_t length = readlink("/proc/self/exe", buffer, capacity - 1);
    if (length < 0 || length >= capacity - 1) return -1;
    buffer[length] = 0;
    return 0;
#endif
}
static volatile sig_atomic_t teamcode_service_stopping = 0;
static inline void teamcode_service_signal(int number) { teamcode_service_stopping = 1; }
static inline const void *teamcode_service_signals(void) {
    struct sigaction action = {0};
    action.sa_handler = teamcode_service_signal;
    sigemptyset(&action.sa_mask);
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGINT, &action, NULL);
    return (const void *)&teamcode_service_stopping;
}
/* cinterop may emit separate wrappers; read the exact flag owned by the installed handler. */
static inline int teamcode_service_stop_requested(const void *flag) { return *(volatile const sig_atomic_t *)flag; }

static inline char **teamcode_environment(void) {
#ifdef __APPLE__
    return *_NSGetEnviron();
#else
    extern char **environ;
    return environ;
#endif
}

/* No shell or fork inside the multithreaded Kotlin runtime; inherit the caller's terminal. */
static inline int teamcode_spawn(const char *command, char *const argv[], char *const envp[], int *child, int quiet) {
    posix_spawnattr_t attributes;
    int result = posix_spawnattr_init(&attributes);
    if (result != 0) return result;
    sigset_t defaults;
    sigemptyset(&defaults);
    sigaddset(&defaults, SIGINT);
    sigaddset(&defaults, SIGTERM);
    sigaddset(&defaults, SIGHUP);
    sigaddset(&defaults, SIGQUIT);
    posix_spawnattr_setsigdefault(&attributes, &defaults);
    posix_spawnattr_setflags(&attributes, POSIX_SPAWN_SETSIGDEF);
    posix_spawn_file_actions_t actions;
    result = posix_spawn_file_actions_init(&actions);
    if (result != 0) { posix_spawnattr_destroy(&attributes); return result; }
    if (quiet) {
        result = posix_spawn_file_actions_addopen(&actions, STDOUT_FILENO, "/dev/null", O_WRONLY, 0);
        if (result == 0) result = posix_spawn_file_actions_addopen(&actions, STDERR_FILENO, "/dev/null", O_WRONLY, 0);
        if (result != 0) { posix_spawn_file_actions_destroy(&actions); posix_spawnattr_destroy(&attributes); return result; }
    }
    pid_t pid;
    result = posix_spawnp(&pid, command, &actions, &attributes, argv, envp);
    posix_spawn_file_actions_destroy(&actions);
    posix_spawnattr_destroy(&attributes);
    if (result == 0) *child = (int)pid;
    return result;
}

/*
 * Spawns a child with piped stdin and stdout and stderr on /dev/null (agent output may hold prompts, never logged).
 * Both parent ends are non-blocking and close-on-exec, so no other child inherits them.
 */
static inline int teamcode_spawn_piped(const char *command, char *const argv[], char *const envp[], int *child, int *input, int *output) {
    int in_pipe[2], out_pipe[2];
    if (pipe(in_pipe) != 0) return errno;
    if (pipe(out_pipe) != 0) { int error = errno; close(in_pipe[0]); close(in_pipe[1]); return error; }
    posix_spawnattr_t attributes;
    int result = posix_spawnattr_init(&attributes);
    if (result != 0) goto fail_pipes;
    sigset_t defaults;
    sigemptyset(&defaults);
    sigaddset(&defaults, SIGINT); sigaddset(&defaults, SIGTERM); sigaddset(&defaults, SIGHUP); sigaddset(&defaults, SIGQUIT); sigaddset(&defaults, SIGPIPE);
    posix_spawnattr_setsigdefault(&attributes, &defaults);
    posix_spawnattr_setflags(&attributes, POSIX_SPAWN_SETSIGDEF);
    posix_spawn_file_actions_t actions;
    result = posix_spawn_file_actions_init(&actions);
    if (result != 0) { posix_spawnattr_destroy(&attributes); goto fail_pipes; }
    result = posix_spawn_file_actions_adddup2(&actions, in_pipe[0], STDIN_FILENO);
    if (result == 0) result = posix_spawn_file_actions_adddup2(&actions, out_pipe[1], STDOUT_FILENO);
    if (result == 0) result = posix_spawn_file_actions_addopen(&actions, STDERR_FILENO, "/dev/null", O_WRONLY, 0);
    if (result == 0) result = posix_spawn_file_actions_addclose(&actions, in_pipe[1]);
    if (result == 0) result = posix_spawn_file_actions_addclose(&actions, out_pipe[0]);
    pid_t pid = 0;
    if (result == 0) result = posix_spawnp(&pid, command, &actions, &attributes, argv, envp);
    posix_spawn_file_actions_destroy(&actions);
    posix_spawnattr_destroy(&attributes);
    if (result != 0) goto fail_pipes;
    close(in_pipe[0]); close(out_pipe[1]);
    fcntl(in_pipe[1], F_SETFL, fcntl(in_pipe[1], F_GETFL) | O_NONBLOCK);
    fcntl(out_pipe[0], F_SETFL, fcntl(out_pipe[0], F_GETFL) | O_NONBLOCK);
    fcntl(in_pipe[1], F_SETFD, FD_CLOEXEC);
    fcntl(out_pipe[0], F_SETFD, FD_CLOEXEC);
    *child = (int)pid; *input = in_pipe[1]; *output = out_pipe[0];
    return 0;
fail_pipes:
    close(in_pipe[0]); close(in_pipe[1]); close(out_pipe[0]); close(out_pipe[1]);
    return result;
}

/*
 * Spawns a child that outlives this process and the terminal: a new session (a new process group where the C library
 * predates POSIX_SPAWN_SETSID), default signal dispositions and an empty mask, stdin on /dev/null, stdout and stderr
 * appended to [log], which the caller created privately beforehand (never created here, never followed if a link).
 */
#ifndef POSIX_SPAWN_SETSID
#ifdef __APPLE__
#define POSIX_SPAWN_SETSID 0x0400
#else
#define POSIX_SPAWN_SETSID 0x80
#endif
#endif
static inline int teamcode_spawn_detached(const char *command, char *const argv[], char *const envp[], const char *log, int *child) {
    int attempt;
    int result = 0;
    for (attempt = 0; attempt < 2; attempt++) {
        posix_spawnattr_t attributes;
        result = posix_spawnattr_init(&attributes);
        if (result != 0) return result;
        sigset_t defaults, empty;
        sigemptyset(&defaults);
        sigaddset(&defaults, SIGINT); sigaddset(&defaults, SIGTERM); sigaddset(&defaults, SIGHUP); sigaddset(&defaults, SIGQUIT); sigaddset(&defaults, SIGPIPE);
        sigemptyset(&empty);
        posix_spawnattr_setsigdefault(&attributes, &defaults);
        posix_spawnattr_setsigmask(&attributes, &empty);
        short flags = POSIX_SPAWN_SETSIGDEF | POSIX_SPAWN_SETSIGMASK;
#ifdef POSIX_SPAWN_CLOEXEC_DEFAULT
        /* Only the three standard streams reach the host, never this process's sockets or files. */
        flags |= POSIX_SPAWN_CLOEXEC_DEFAULT;
#endif
        /* An older C library refuses the new-session flag (EINVAL): the second attempt takes a new process group. */
        if (attempt == 0) flags |= POSIX_SPAWN_SETSID;
        else { flags |= POSIX_SPAWN_SETPGROUP; posix_spawnattr_setpgroup(&attributes, 0); }
        posix_spawnattr_setflags(&attributes, flags);
        posix_spawn_file_actions_t actions;
        result = posix_spawn_file_actions_init(&actions);
        if (result != 0) { posix_spawnattr_destroy(&attributes); return result; }
        result = posix_spawn_file_actions_addopen(&actions, STDIN_FILENO, "/dev/null", O_RDONLY, 0);
        if (result == 0) result = posix_spawn_file_actions_addopen(&actions, STDOUT_FILENO, log, O_WRONLY | O_APPEND | O_NOFOLLOW, 0);
        if (result == 0) result = posix_spawn_file_actions_adddup2(&actions, STDOUT_FILENO, STDERR_FILENO);
        pid_t pid = 0;
        if (result == 0) result = posix_spawnp(&pid, command, &actions, &attributes, argv, envp);
        posix_spawn_file_actions_destroy(&actions);
        posix_spawnattr_destroy(&attributes);
        if (result == 0) { *child = (int)pid; return 0; }
        if (result != EINVAL) return result;
    }
    return result;
}

/* Non-blocking pipe I/O: returns bytes moved, 0 at end of stream (read), -1 when it would block, -2 on error. */
static inline int teamcode_pipe_read(int fd, unsigned char *buffer, int capacity) {
    ssize_t count = read(fd, buffer, (size_t)capacity);
    if (count >= 0) return (int)count;
    return (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) ? -1 : -2;
}
static inline int teamcode_pipe_write(int fd, const unsigned char *buffer, int length) {
    ssize_t count = write(fd, buffer, (size_t)length);
    if (count >= 0) return (int)count;
    return (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) ? -1 : -2;
}
static inline void teamcode_pipe_close(int fd) { close(fd); }
/* A child that exits must not kill the runner with SIGPIPE; writes then fail with EPIPE instead. */
static inline void teamcode_ignore_sigpipe(void) { signal(SIGPIPE, SIG_IGN); }

/* Poll only this exact owned child. A reaped PID is never signalled. */
static inline int teamcode_poll(int child, int *code) {
    int status;
    pid_t result = waitpid((pid_t)child, &status, WNOHANG);
    if (result == 0 || (result < 0 && errno == EINTR)) return 0;
    if (result < 0) return -1;
    if (WIFEXITED(status)) *code = WEXITSTATUS(status);
    else if (WIFSIGNALED(status)) *code = 128 + WTERMSIG(status);
    else return 0;
    return 1;
}
static inline void teamcode_terminate(int child, int force) { kill((pid_t)child, force ? SIGKILL : SIGTERM); }
/*
 * Unix domain sockets for the in-pod agent supervisor and credential broker. A listener is created with mode 0660 (the
 * umask is narrowed around bind, so the socket never exists with wider rights) and is non-blocking, as are accepted
 * and connected descriptors; all are close-on-exec. Returns a descriptor, or -1 with errno set.
 */
static inline int teamcode_unix_address(const char *path, struct sockaddr_un *address) {
    memset(address, 0, sizeof(*address));
    address->sun_family = AF_UNIX;
    if (strlen(path) >= sizeof(address->sun_path)) { errno = ENAMETOOLONG; return -1; }
    strcpy(address->sun_path, path);
    return 0;
}
static inline int teamcode_unix_prepare(int fd) {
    if (fd < 0) return -1;
    fcntl(fd, F_SETFD, FD_CLOEXEC);
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
    return fd;
}
static inline int teamcode_unix_listen(const char *path) {
    struct sockaddr_un address;
    if (teamcode_unix_address(path, &address) != 0) return -1;
    int fd = teamcode_unix_prepare(socket(AF_UNIX, SOCK_STREAM, 0));
    if (fd < 0) return -1;
    unlink(path);
    mode_t previous = umask(0117);
    int bound = bind(fd, (struct sockaddr *)&address, sizeof(address));
    int error = errno;
    umask(previous);
    if (bound != 0 || listen(fd, 16) != 0) { if (bound == 0) error = errno; close(fd); errno = error; return -1; }
    return fd;
}
/* A new connection, -1 when none is waiting, -2 on error. */
static inline int teamcode_unix_accept(int listener) {
    int fd = accept(listener, NULL, NULL);
    if (fd >= 0) return teamcode_unix_prepare(fd);
    return (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR || errno == ECONNABORTED) ? -1 : -2;
}
static inline int teamcode_unix_connect(const char *path) {
    struct sockaddr_un address;
    if (teamcode_unix_address(path, &address) != 0) return -1;
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    if (connect(fd, (struct sockaddr *)&address, sizeof(address)) != 0) { int error = errno; close(fd); errno = error; return -1; }
    return teamcode_unix_prepare(fd);
}
static inline void teamcode_unix_remove(const char *path) { unlink(path); }

/*
 * Artifact sources: opens the root, then each component below it with O_NOFOLLOW, so no symbolic link anywhere under
 * the root (in the path or as the file) can redirect the read. Returns the descriptor of a regular file and its size,
 * or a negative code with *failed the index of the refused component (-1 for the root): -1 root, -2 symbolic link or
 * not a directory, -3 missing, -4 not a regular file, -5 permission denied, -6 other.
 */
static inline int teamcode_open_nofollow(const char *root, const char *const *components, int count, int *failed, long long *size) {
    *failed = -1;
    if (count < 1) return -6;
    int dir = open(root, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (dir < 0) return -1;
    for (int i = 0; i < count; i++) {
        int last = i == count - 1;
        /* O_NONBLOCK so a FIFO planted as the file never blocks the open; fstat then refuses it. */
        int next = openat(dir, components[i], O_RDONLY | O_NOFOLLOW | O_CLOEXEC | (last ? O_NONBLOCK : O_DIRECTORY));
        int error = errno;
        close(dir);
        if (next < 0) {
            *failed = i;
            if (error == ELOOP || error == ENOTDIR || error == EMLINK) return -2;
            if (error == ENOENT) return -3;
            if (error == EACCES || error == EPERM) return -5;
            return -6;
        }
        dir = next;
    }
    struct stat info;
    if (fstat(dir, &info) != 0 || !S_ISREG(info.st_mode)) { close(dir); *failed = count - 1; return -4; }
    *size = (long long)info.st_size;
    return dir;
}
#endif
