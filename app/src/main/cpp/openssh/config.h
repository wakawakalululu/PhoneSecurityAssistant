/* config.h for openssh/sftp-server on Android NDK API 26 (Bionic) */
#ifndef CONFIG_H
#define CONFIG_H

/* System headers available in Bionic */
#define HAVE_LIMITS_H 1
#define HAVE_ENDIAN_H 1
#define HAVE_FCNTL_H 1
#define HAVE_PATHS_H 1
#define HAVE_STRINGS_H 1
#define HAVE_STDINT_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TIME_H 1
#define HAVE_UTIME_H 1
#define HAVE_SYS_CDEFS_H 1
#define HAVE_SYS_SYSMACROS_H 1
#define HAVE_SYS_UN_H 1
#define HAVE_SHADOW_H 1

/* Types available in Bionic */
#define HAVE_INTXX_T 1
#define HAVE_U_INTXX_T 1
#define HAVE_UINTXX_T 1
#define HAVE_INT64_T 1
#define HAVE_U_INT64_T 1
#define HAVE_INTMAX_T 1
#define HAVE_UINTMAX_T 1
#define HAVE_U_CHAR 1
#define HAVE_U_INT 1
#define HAVE_SIZE_T 1
#define HAVE_SSIZE_T 1
#define HAVE_CLOCK_T 1
#define HAVE_SA_FAMILY_T 1
#define HAVE_PID_T 1
#define HAVE_SIG_ATOMIC_T 1
#define HAVE_MODE_T 1
#define HAVE_IN_ADDR_T 1
#define HAVE_IN_PORT_T 1
#define HAVE_FD_MASK 1

/* sockaddr_storage family field */
#define HAVE_SS_FAMILY_IN_SS 1

/* Functions available in Bionic */
#define HAVE_ARC4RANDOM 1
#define HAVE_ARC4RANDOM_STIR 1
#define HAVE_ARC4RANDOM_UNIFORM 1
#define HAVE_MEMMOVE 1
#define HAVE_VA_COPY 1
#define HAVE___VA_COPY 1
#define HAVE_GETADDRINFO 1
#define HAVE_FREEADDRINFO 1
#define HAVE_GAI_STRERROR 1
#define HAVE_NGETADDRINFO 1
#define HAVE_SYSCONF 1
#define HAVE_BCOPY 1

/* Declarations */
#define HAVE_DECL_AI_NUMERICSERV 1
#define HAVE_DECL_HTOBE32 1
#define HAVE_DECL_HTOLE64 1
#define HAVE_DECL_LE32TOH 1
#define HAVE_DECL_LE64TOH 1
#define HAVE_DECL_MAXSYMLINKS 1
#define HAVE_DECL_NFDBITS 1
#define HAVE_DECL_HOWMANY 1
#define HAVE_DECL_O_NONBLOCK 1
#define HAVE_DECL_SHUT_RD 1
#define HAVE_DECL_UINT32_MAX 1
#define HAVE_DECL_INFINITY 1
#define HAVE_DECL_OFFSETOF 1
#define HAVE_DECL___BUILTIN_INFF 1

/* Compiler attributes (clang supports these) */
#define HAVE_ATTRIBUTE__BOUNDED__ 1
#define HAVE_ATTRIBUTE__NONNULL__ 1
#define HAVE_ATTRIBUTE__NONSTRING__ 1
#define HAVE_ATTRIBUTE__SENTINEL__ 1

/* Misc */
#define HAVE___FUNCTION__ 1
#define SIZEOF_TIME_T 8
#define SIZEOF_LONG_LONG_INT 8

#endif /* CONFIG_H */
