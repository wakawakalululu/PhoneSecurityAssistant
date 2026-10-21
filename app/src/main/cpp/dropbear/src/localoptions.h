#ifndef LOCALOPTIONS_H
#define LOCALOPTIONS_H

#define DROPBEAR_SERVER 1

#define DROPBEAR_SVR_PASSWORD_AUTH 0
#define DROPBEAR_SVR_DROP_PRIVS 0
#define DROPBEAR_SVR_MULTIUSER 0

/* Bionic getrandom() - not declared in <sys/random.h> for API < 28 */
#include <sys/types.h>
extern ssize_t getrandom(void *buf, size_t buflen, unsigned int flags);

/* Android app integration symbols (see jni-dropbear.h) */
#define AUTHORIZED_KEYS_FILE "authorized_keys"

extern const char *sshd4a_shell_exe;
extern const char *sshd4a_home_path;
char *sshd4a_conf_file(const char *fn);
char *sshd4a_exe_to_lib(const char *cmd);
int sshd4a_enable_service_shell_access();
void sshd4a_set_env();
void sshd4a_svr_authinitialise();
void sshd4a_svr_auth_password(const char *password, unsigned int passwordlen,
                              char **passwdcrypt, char **testcrypt);

#endif
