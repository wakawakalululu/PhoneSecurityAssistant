#ifndef LOCALOPTIONS_H
#define LOCALOPTIONS_H

#define DROPBEAR_SVR_PASSWORD_AUTH 0
#define DROPBEAR_SVR_DROP_PRIVS 0
#define DROPBEAR_SVR_MULTIUSER 0

/* Android app integration symbols (see jni-dropbear.h) */
extern const char *sshd4a_shell_exe;
extern const char *sshd4a_home_path;
char *sshd4a_exe_to_lib(const char *cmd);
int sshd4a_enable_service_shell_access();
void sshd4a_set_env();

#endif
