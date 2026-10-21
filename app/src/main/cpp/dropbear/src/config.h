#ifndef DROPBEAR_CONFIG_H
#define DROPBEAR_CONFIG_H

/* Android (Bionic libc) config for dropbear.
 * Generated manually for NDK build (no autotools configure step).
 * Most defaults come from sysoptions.h and default_options_guard.h. */

/* Bionic libc provides these: */
#define HAVE_CLOCK_GETTIME 1
#define HAVE_GETADDRINFO 1
#define HAVE_GETNAMEINFO 1
#define HAVE_FREEADDRINFO 1
#define HAVE_GAI_STRERROR 1
#define HAVE_CONST_GAI_STRERROR_PROTO 1
#define HAVE_ENDIAN_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_NETDB_H 1
#define HAVE_FORK 1
#define HAVE_DAEMON 1
#define HAVE_BASENAME 1
#define HAVE_CLEARENV 1
#define HAVE_FCHMOD 1
#define HAVE_LIBGEN_H 1
#define HAVE_GETRANDOM 1

/* Bionic provides RFC 2553 / IPv6 structs (fake-rfc2553.h guards) */
#define HAVE_STRUCT_SOCKADDR_STORAGE 1
#define HAVE_STRUCT_IN6_ADDR 1
#define HAVE_STRUCT_SOCKADDR_IN6 1
#define HAVE_STRUCT_ADDRINFO 1

/* Bionic does not provide these: */
/* #undef HAVE_FEXECVE */
/* #undef HAVE_CRYPT_H */
/* #undef HAVE_LOGIN */
/* #undef HAVE_LASTLOG_H */
/* #undef HAVE_LIBUTIL_H */
/* #undef HAVE_GETTTYENT */
/* #undef HAVE_GETUSERSHELL */
/* #undef HAVE_MACH_ABSOLUTE_TIME */
/* #undef HAVE_MATCH_H */
/* #undef HAVE_MEMSET_S */
/* #undef HAVE_DEV_PTMS_AND_PTDX */

/* Not using syslog on Android */
#define DISABLE_SYSLOG 1

/* No utmp/wtmp/lastlog on Android */
#define DISABLE_UTMP 1
#define DISABLE_UTMPX 1
#define DISABLE_WTMP 1
#define DISABLE_WTMPX 1
#define DISABLE_LASTLOG 1
#define DISABLE_PUTUTLINE 1
#define DISABLE_PUTUTXLINE 1

/* No PAM on Android (using our own auth) */
#define DISABLE_PAM 1

#endif /* DROPBEAR_CONFIG_H */
