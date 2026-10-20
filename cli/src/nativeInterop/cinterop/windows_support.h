#ifndef TEAMCODE_WINDOWS_SUPPORT_H
#define TEAMCODE_WINDOWS_SUPPORT_H
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0601
#endif
#include <windows.h>
#include <bcrypt.h>
#include <wincrypt.h>
#include <aclapi.h>
#include <sddl.h>
#include <shellapi.h>
#include <stdlib.h>
#include <wchar.h>

/* SubjectPublicKeyInfo import uses Crypt32; signature verification stays in BCrypt. */
static inline int teamcode_release_verify(const unsigned char *spki, size_t spki_size,
        const unsigned char *pkcs1, size_t pkcs1_size, const unsigned char *digest, size_t digest_size,
        const unsigned char *signature, size_t signature_size) {
    if (!spki || spki_size == 0 || spki_size > 2048 || digest_size != 32 || signature_size != 384) return 0;
    CERT_PUBLIC_KEY_INFO *information = NULL;
    DWORD size = 0;
    if (!CryptDecodeObjectEx(X509_ASN_ENCODING, X509_PUBLIC_KEY_INFO, spki, (DWORD)spki_size,
            CRYPT_DECODE_ALLOC_FLAG, NULL, &information, &size)) return 0;
    BCRYPT_KEY_HANDLE key = NULL;
    BOOL imported = CryptImportPublicKeyInfoEx2(X509_ASN_ENCODING, information, 0, NULL, &key);
    LocalFree(information);
    if (!imported) return 0;
    BCRYPT_PSS_PADDING_INFO padding = {BCRYPT_SHA256_ALGORITHM, 32};
    NTSTATUS result = BCryptVerifySignature(key, &padding, (PUCHAR)digest, 32,
        (PUCHAR)signature, 384, BCRYPT_PAD_PSS);
    BCryptDestroyKey(key);
    return result == 0;
}

static inline int teamcode_windows_hook_input(unsigned char *buffer, int capacity) {
    HANDLE input = GetStdHandle(STD_INPUT_HANDLE);
    DWORD type = GetFileType(input);
    if (type != FILE_TYPE_PIPE && type != FILE_TYPE_DISK) return -1;
    ULONGLONG start = GetTickCount64();
    int total = 0;
    for (;;) {
        if (GetTickCount64() - start >= 1000) return -1;
        DWORD available = (DWORD)(capacity - total);
        if (type == FILE_TYPE_PIPE) {
            if (!PeekNamedPipe(input, NULL, 0, NULL, &available, NULL))
                return GetLastError() == ERROR_BROKEN_PIPE ? total : -1;
            if (available == 0) { Sleep(10); continue; }
            if (available > (DWORD)(capacity - total)) available = (DWORD)(capacity - total);
        }
        DWORD count = 0;
        if (!ReadFile(input, buffer + total, available, &count, NULL))
            return GetLastError() == ERROR_BROKEN_PIPE ? total : -1;
        if (count == 0) return total;
        total += (int)count;
        if (total >= capacity) return -1;
    }
}

static inline wchar_t **teamcode_windows_arguments(int *count) { return CommandLineToArgvW(GetCommandLineW(), count); }

static inline int teamcode_windows_random(void *buffer, unsigned long count) {
    return BCryptGenRandom(NULL, (PUCHAR)buffer, count, BCRYPT_USE_SYSTEM_PREFERRED_RNG) >= 0;
}

/* Caller frees the returned LocalAlloc SID string. Never expose token credentials. */
static inline wchar_t *teamcode_windows_user_sid(void) {
    HANDLE token = NULL;
    DWORD size = 0;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return NULL;
    GetTokenInformation(token, TokenUser, NULL, 0, &size);
    TOKEN_USER *user = (TOKEN_USER *)malloc(size);
    wchar_t *sid = NULL;
    if (user && GetTokenInformation(token, TokenUser, user, size, &size))
        ConvertSidToStringSidW(user->User.Sid, &sid);
    free(user);
    CloseHandle(token);
    return sid;
}

static inline int teamcode_windows_owner_matches(const wchar_t *path, PSID expected) {
    PSECURITY_DESCRIPTOR descriptor = NULL;
    PSID owner = NULL;
    DWORD result = GetNamedSecurityInfoW((wchar_t *)path, SE_FILE_OBJECT, OWNER_SECURITY_INFORMATION,
        &owner, NULL, NULL, NULL, &descriptor);
    int matches = result == ERROR_SUCCESS && owner && EqualSid(owner, expected);
    if (descriptor) LocalFree(descriptor);
    return matches;
}

/* An elevated token may default new-file ownership to Administrators, not its user SID. */
static inline PSECURITY_DESCRIPTOR teamcode_windows_private_descriptor(void) {
    wchar_t *sid = teamcode_windows_user_sid();
    if (!sid) return NULL;
    size_t capacity = 64 + 2 * wcslen(sid);
    wchar_t *sddl = (wchar_t *)calloc(capacity, sizeof(wchar_t));
    PSECURITY_DESCRIPTOR descriptor = NULL;
    if (sddl) {
        swprintf(sddl, capacity, L"O:%lsD:P(A;OICI;FA;;;%ls)", sid, sid);
        if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, SDDL_REVISION_1, &descriptor, NULL)) descriptor = NULL;
    }
    free(sddl);
    LocalFree(sid);
    return descriptor;
}

/* Protected DACL with one explicit allow entry for the current user, on both directory and file. */
static inline int teamcode_windows_write_private(const wchar_t *path, const wchar_t *parent,
        const wchar_t *temporary, const void *bytes, unsigned long length, int replace_existing) {
    PSECURITY_DESCRIPTOR descriptor = teamcode_windows_private_descriptor();
    if (!descriptor) return 2;
    int success = 0;
    HANDLE file = INVALID_HANDLE_VALUE;
    int created = 0;
    int stage = 2;
    SECURITY_ATTRIBUTES attributes = { sizeof(SECURITY_ATTRIBUTES), descriptor, FALSE };
    PSID owner = NULL;
    PACL dacl = NULL;
    BOOL defaulted = FALSE, present = FALSE;
    if (!GetSecurityDescriptorOwner(descriptor, &owner, &defaulted) ||
        !GetSecurityDescriptorDacl(descriptor, &present, &dacl, &defaulted) || !present || !dacl) goto cleanup;
    stage = 3;
    if (!CreateDirectoryW(parent, &attributes)) {
        DWORD error = GetLastError();
        if (error != ERROR_ALREADY_EXISTS) { stage = 30000 + (int)error; goto cleanup; }
    }
    stage = 4;
    DWORD parent_flags = GetFileAttributesW(parent);
    if (parent_flags == INVALID_FILE_ATTRIBUTES || !(parent_flags & FILE_ATTRIBUTE_DIRECTORY) ||
        (parent_flags & FILE_ATTRIBUTE_REPARSE_POINT) || !teamcode_windows_owner_matches(parent, owner)) goto cleanup;
    stage = 5;
    if (SetNamedSecurityInfoW((wchar_t *)parent, SE_FILE_OBJECT,
        DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION, NULL, NULL, dacl, NULL) != ERROR_SUCCESS) goto cleanup;
    DWORD destination_flags = GetFileAttributesW(path);
    stage = 6;
    if (destination_flags != INVALID_FILE_ATTRIBUTES &&
        ((destination_flags & (FILE_ATTRIBUTE_REPARSE_POINT | FILE_ATTRIBUTE_DIRECTORY)) || !teamcode_windows_owner_matches(path, owner))) goto cleanup;
    stage = 7;
    file = CreateFileW(temporary, GENERIC_WRITE, 0, &attributes, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) goto cleanup;
    created = 1;
    stage = 8;
    DWORD offset = 0;
    while (offset < length) {
        DWORD written = 0;
        if (!WriteFile(file, (const char *)bytes + offset, length - offset, &written, NULL) || written == 0) goto cleanup;
        offset += written;
    }
    stage = 9;
    if (!FlushFileBuffers(file)) goto cleanup;
    CloseHandle(file);
    file = INVALID_HANDLE_VALUE;
    stage = 10;
    for (int attempt = 0; attempt < 20; attempt++) {
        DWORD flags = MOVEFILE_WRITE_THROUGH | (replace_existing ? MOVEFILE_REPLACE_EXISTING : 0);
        if (MoveFileExW(temporary, path, flags)) { success = 1; break; }
        DWORD error = GetLastError();
        if (!replace_existing && (error == ERROR_ALREADY_EXISTS || error == ERROR_FILE_EXISTS)) { stage = -1; goto cleanup; }
        if (error != ERROR_SHARING_VIOLATION && error != ERROR_ACCESS_DENIED) break;
        Sleep(25); /* A concurrent token read may briefly hold a non-delete-sharing handle. */
    }
cleanup:
    if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
    if (created && !success) DeleteFileW(temporary);
    if (descriptor) LocalFree(descriptor);
    return success ? 0 : stage;
}

static inline int teamcode_windows_install_private_path(const wchar_t *path, int directory) {
    DWORD flags = GetFileAttributesW(path);
    if (flags == INVALID_FILE_ATTRIBUTES || (flags & FILE_ATTRIBUTE_REPARSE_POINT) ||
        (((flags & FILE_ATTRIBUTE_DIRECTORY) != 0) != (directory != 0))) return 0;
    wchar_t *text = teamcode_windows_user_sid();
    PSID expected = NULL, owner = NULL;
    PACL dacl = NULL;
    PSECURITY_DESCRIPTOR descriptor = NULL;
    int valid = text && ConvertStringSidToSidW(text, &expected) &&
        GetNamedSecurityInfoW((wchar_t *)path, SE_FILE_OBJECT, OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
            &owner, NULL, &dacl, NULL, &descriptor) == ERROR_SUCCESS && owner && EqualSid(owner, expected) && dacl && dacl->AceCount == 1;
    if (valid) {
        ACCESS_ALLOWED_ACE *ace = NULL;
        valid = GetAce(dacl, 0, (void **)&ace) && ace && ace->Header.AceType == ACCESS_ALLOWED_ACE_TYPE &&
            EqualSid((PSID)&ace->SidStart, expected);
    }
    if (descriptor) LocalFree(descriptor);
    if (expected) LocalFree(expected);
    if (text) LocalFree(text);
    return valid;
}
static inline int teamcode_windows_install_owned(HANDLE file) {
    BY_HANDLE_FILE_INFORMATION information;
    if (!GetFileInformationByHandle(file, &information) || information.nNumberOfLinks != 1 ||
        (information.dwFileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT))) return 0;
    wchar_t *text = teamcode_windows_user_sid();
    PSID expected = NULL, actual = NULL;
    PSECURITY_DESCRIPTOR descriptor = NULL;
    int valid = text && ConvertStringSidToSidW(text, &expected) &&
        GetSecurityInfo(file, SE_FILE_OBJECT, OWNER_SECURITY_INFORMATION,
            &actual, NULL, NULL, NULL, &descriptor) == ERROR_SUCCESS && actual && EqualSid(actual, expected);
    if (descriptor) LocalFree(descriptor);
    if (expected) LocalFree(expected);
    if (text) LocalFree(text);
    return valid;
}
/* Share the persistent file; only the one-byte lock region controls shared/exclusive access. */
static inline int teamcode_windows_install_lock(const wchar_t *path, int exclusive, void **handle) {
    PSECURITY_DESCRIPTOR descriptor = teamcode_windows_private_descriptor();
    if (!descriptor) return -1;
    SECURITY_ATTRIBUTES attributes = { sizeof(SECURITY_ATTRIBUTES), descriptor, FALSE };
    HANDLE file = CreateFileW(path, GENERIC_READ | GENERIC_WRITE | READ_CONTROL,
        FILE_SHARE_READ | FILE_SHARE_WRITE, &attributes, OPEN_ALWAYS, FILE_FLAG_OPEN_REPARSE_POINT, NULL);
    LocalFree(descriptor);
    if (file == INVALID_HANDLE_VALUE) return -1;
    if (!teamcode_windows_install_owned(file)) { CloseHandle(file); return -1; }
    OVERLAPPED position = {0};
    if (!LockFileEx(file, LOCKFILE_FAIL_IMMEDIATELY | (exclusive ? LOCKFILE_EXCLUSIVE_LOCK : 0), 0, 1, 0, &position)) {
        DWORD error = GetLastError();
        CloseHandle(file);
        return error == ERROR_LOCK_VIOLATION ? 0 : -1;
    }
    *handle = file;
    return 1;
}
static inline void teamcode_windows_install_unlock(void *handle) { CloseHandle((HANDLE)handle); }
static inline int teamcode_windows_install_seal(const wchar_t *path) {
    HANDLE file = CreateFileW(path, GENERIC_READ | GENERIC_WRITE | READ_CONTROL, FILE_SHARE_READ,
        NULL, OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT, NULL);
    if (file == INVALID_HANDLE_VALUE) return -1;
    int valid = teamcode_windows_install_owned(file) && FlushFileBuffers(file);
    CloseHandle(file);
    return valid ? 0 : -1;
}

/* Duplicate only standard streams; do not make arbitrary parent handles inheritable. */
static inline HANDLE teamcode_windows_standard(DWORD kind, int quiet) {
    HANDLE result = NULL;
    if (quiet && kind != STD_INPUT_HANDLE) {
        SECURITY_ATTRIBUTES attributes = { sizeof(SECURITY_ATTRIBUTES), NULL, TRUE };
        return CreateFileW(L"NUL", GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, &attributes, OPEN_EXISTING, 0, NULL);
    }
    HANDLE source = GetStdHandle(kind);
    if (!source || source == INVALID_HANDLE_VALUE) {
        SECURITY_ATTRIBUTES attributes = { sizeof(SECURITY_ATTRIBUTES), NULL, TRUE };
        return CreateFileW(L"NUL", kind == STD_INPUT_HANDLE ? GENERIC_READ : GENERIC_WRITE,
            FILE_SHARE_READ | FILE_SHARE_WRITE, &attributes, OPEN_EXISTING, 0, NULL);
    }
    if (!DuplicateHandle(GetCurrentProcess(), source, GetCurrentProcess(), &result, 0, TRUE, DUPLICATE_SAME_ACCESS)) return NULL;
    return result;
}

typedef struct {
    HANDLE process;
    HANDLE job;
} teamcode_windows_child;

/* A private job owns only this child and its descendants, including npm's native agent subprocess.
 * Assign before resuming so no descendant can escape between process creation and job assignment. */
static inline void *teamcode_windows_spawn(const wchar_t *application, wchar_t *command_line,
        const wchar_t *environment, int quiet) {
    STARTUPINFOEXW startup = {0};
    startup.StartupInfo.cb = sizeof(startup);
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    HANDLE handles[3] = { teamcode_windows_standard(STD_INPUT_HANDLE, quiet),
        teamcode_windows_standard(STD_OUTPUT_HANDLE, quiet), teamcode_windows_standard(STD_ERROR_HANDLE, quiet) };
    PROCESS_INFORMATION process = {0};
    SIZE_T size = 0;
    int initialized = 0;
    teamcode_windows_child *child = NULL;
    HANDLE job = CreateJobObjectW(NULL, NULL);
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits = {0};
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if (!job || !SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof(limits))) goto cleanup;
    for (int i = 0; i < 3; i++) if (!handles[i] || handles[i] == INVALID_HANDLE_VALUE) goto cleanup;
    startup.StartupInfo.hStdInput = handles[0];
    startup.StartupInfo.hStdOutput = handles[1];
    startup.StartupInfo.hStdError = handles[2];
    InitializeProcThreadAttributeList(NULL, 1, 0, &size);
    startup.lpAttributeList = (LPPROC_THREAD_ATTRIBUTE_LIST)malloc(size);
    if (!startup.lpAttributeList || !InitializeProcThreadAttributeList(startup.lpAttributeList, 1, 0, &size)) goto cleanup;
    initialized = 1;
    if (!UpdateProcThreadAttribute(startup.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
        handles, sizeof(handles), NULL, NULL)) goto cleanup;
    if (!CreateProcessW(application, command_line, NULL, NULL, TRUE,
        CREATE_UNICODE_ENVIRONMENT | EXTENDED_STARTUPINFO_PRESENT | CREATE_SUSPENDED, (void *)environment, NULL,
        &startup.StartupInfo, &process)) goto cleanup;
    if (!AssignProcessToJobObject(job, process.hProcess)) goto cleanup;
    child = (teamcode_windows_child *)calloc(1, sizeof(teamcode_windows_child));
    if (!child) goto cleanup;
    if (ResumeThread(process.hThread) == (DWORD)-1) { free(child); child = NULL; goto cleanup; }
    child->process = process.hProcess;
    child->job = job;
cleanup:
    if (!child && process.hProcess) { TerminateProcess(process.hProcess, 130); CloseHandle(process.hProcess); }
    if (!child && job) CloseHandle(job);
    if (process.hThread) CloseHandle(process.hThread);
    if (initialized) DeleteProcThreadAttributeList(startup.lpAttributeList);
    free(startup.lpAttributeList);
    for (int i = 0; i < 3; i++) if (handles[i] && handles[i] != INVALID_HANDLE_VALUE) CloseHandle(handles[i]);
    return child;
}
static inline int teamcode_windows_poll(void *process, unsigned long *code) {
    teamcode_windows_child *child = (teamcode_windows_child *)process;
    DWORD status = WaitForSingleObject(child->process, 0);
    if (status == WAIT_TIMEOUT) return 0;
    if (status != WAIT_OBJECT_0 || !GetExitCodeProcess(child->process, code)) return -1;
    return 1;
}
static inline void teamcode_windows_close(void *process) {
    teamcode_windows_child *child = (teamcode_windows_child *)process;
    CloseHandle(child->process);
    CloseHandle(child->job);
    free(child);
}
static inline void teamcode_windows_terminate(void *process) {
    TerminateJobObject(((teamcode_windows_child *)process)->job, 130);
}
/* Current user's PATH. Returns 1 and a NUL-terminated value, 0 if absent, -1 on failure; *expandable is 1 for REG_EXPAND_SZ. */
static inline int teamcode_windows_user_path_read(wchar_t *buffer, DWORD capacity, int *expandable) {
    HKEY key;
    LONG status = RegOpenKeyExW(HKEY_CURRENT_USER, L"Environment", 0, KEY_QUERY_VALUE, &key);
    if (status == ERROR_FILE_NOT_FOUND) return 0;
    if (status != ERROR_SUCCESS) return -1;
    DWORD type = 0, size = capacity * sizeof(wchar_t);
    status = RegQueryValueExW(key, L"Path", NULL, &type, (LPBYTE)buffer, &size);
    RegCloseKey(key);
    if (status == ERROR_FILE_NOT_FOUND) return 0;
    if (status != ERROR_SUCCESS || (type != REG_SZ && type != REG_EXPAND_SZ)) return -1;
    buffer[size / sizeof(wchar_t) < capacity ? size / sizeof(wchar_t) : capacity] = 0;
    *expandable = type == REG_EXPAND_SZ;
    return 1;
}

/* Replace the current user's PATH, then tell running applications (Explorer, new terminals) to reload the environment. */
static inline int teamcode_windows_user_path_write(const wchar_t *value, int expandable) {
    HKEY key;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, L"Environment", 0, NULL, 0, KEY_SET_VALUE, NULL, &key, NULL) != ERROR_SUCCESS) return -1;
    LONG status = RegSetValueExW(key, L"Path", 0, expandable ? REG_EXPAND_SZ : REG_SZ,
        (const BYTE *)value, (DWORD)((wcslen(value) + 1) * sizeof(wchar_t)));
    RegCloseKey(key);
    if (status != ERROR_SUCCESS) return -1;
    DWORD ignored;
    SendMessageTimeoutW(HWND_BROADCAST, WM_SETTINGCHANGE, 0, (LPARAM)L"Environment", SMTO_ABORTIFHUNG, 5000, (PDWORD_PTR)&ignored);
    return 1;
}
#endif
