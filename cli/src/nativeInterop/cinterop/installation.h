#ifndef TEAMCODE_INSTALLATION_H
#define TEAMCODE_INSTALLATION_H
#include <sys/file.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>

static inline int teamcode_install_private_path(const char *path, int directory) {
    struct stat information;
    return lstat(path, &information) == 0 && information.st_uid == geteuid() &&
        (information.st_mode & 0077) == 0 &&
        (directory ? S_ISDIR(information.st_mode) : (S_ISREG(information.st_mode) && information.st_nlink == 1));
}

/* A persistent inode prevents competing processes from locking different recreated files. */
static inline int teamcode_install_lock(const char *path, int exclusive, int *descriptor) {
    int file = open(path, O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (file < 0) return -1;
    struct stat information;
    if (fstat(file, &information) != 0 || !S_ISREG(information.st_mode) ||
            information.st_uid != geteuid() || information.st_nlink != 1 || fchmod(file, 0600) != 0) {
        close(file); return -1;
    }
    if (flock(file, (exclusive ? LOCK_EX : LOCK_SH) | LOCK_NB) != 0) {
        int busy = errno == EWOULDBLOCK || errno == EAGAIN;
        close(file); return busy ? 0 : -1;
    }
    *descriptor = file;
    return 1;
}
static inline void teamcode_install_unlock(int descriptor) { close(descriptor); }

static inline int teamcode_install_seal(const char *path) {
    int file = open(path, O_RDWR | O_NOFOLLOW | O_CLOEXEC);
    if (file < 0) return -1;
    struct stat information;
    int valid = fstat(file, &information) == 0 && S_ISREG(information.st_mode) &&
        information.st_uid == geteuid() && information.st_nlink == 1 &&
        fsync(file) == 0 && fchmod(file, 0700) == 0;
    close(file);
    return valid ? 0 : -1;
}
static inline int teamcode_install_sync_directory(const char *path) {
    int file = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (file < 0) return -1;
    struct stat information;
    int valid = fstat(file, &information) == 0 && S_ISDIR(information.st_mode) &&
        information.st_uid == geteuid() && fsync(file) == 0;
    close(file);
    return valid ? 0 : -1;
}
#endif
