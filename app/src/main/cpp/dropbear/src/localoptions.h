#ifndef LOCALOPTIONS_H
#define LOCALOPTIONS_H

/* Android (Bionic libc) overrides for dropbear build */

/* Bionic has no crypt() - disable password auth, use pubkey only */
#define DROPBEAR_SVR_PASSWORD_AUTH 0

/* No privilege dropping on Android (single UID app process) */
#define DROPBEAR_SVR_DROP_PRIVS 0

/* Single-user mode: Android app runs as one UID, not multiuser */
#define DROPBEAR_SVR_MULTIUSER 0

#endif
