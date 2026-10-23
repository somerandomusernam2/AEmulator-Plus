// Smoke test of aemushim_x86.c on any x86-64 Linux that still runs 32-bit code (no libc, no NDK):
//   gcc -m32 -O1 -nostdlib -ffreestanding -fno-builtin -fno-stack-protector -fno-pie -no-pie -static \
//       -fno-tree-loop-distribute-patterns -mstackrealign -o /tmp/aemushim-x86-smoke tests/aemushim-x86-smoke.c && /tmp/aemushim-x86-smoke
// Prints PASS/FAIL per check, exit status 0 when all pass. Works in /tmp/files/images/j7ekmu/root (stands in for the image root).
#include "../aemushim_x86.c"
static int e; int *__errno(void){return &e;}
static long w(int fd,const char*s){int n=0;while(s[n])n++;return sys3(4,fd,(long)s,n);}
static int eq(const char*a,const char*b,int n){for(int i=0;i<n;i++)if(a[i]!=b[i])return 0;return b[n]==0;}
static int check(const char*name,const char*path,const char*want){
  char b[256]; for(int i=0;i<256;i++)b[i]='X';
  long r=readlink(path,b,sizeof b);
  int ok = r==(long)(({int n=0;while(want[n])n++;n;})) && eq(b,want,(int)r);
  w(1,ok?"PASS ":"FAIL ");w(1,name);w(1,"\n");return ok;}
void _start(void){
  int bad=0;
  const char*root="/tmp/files/images/j7ekmu/root";
  sys3(39,(long)"/tmp/files",0,0);sys3(39,(long)"/tmp/files/images",0,0);sys3(39,(long)"/tmp/files/images/j7ekmu",0,0);sys3(39,(long)root,0,0);sys3(39,(long)"/tmp/files/images/j7ekmu/root/dev",0,0);sys3(39,(long)"/tmp/files/images/j7ekmu/root/dev/socket",0,0);sys3(10,(long)"/tmp/files/images/j7ekmu/root/dev/socket/zygote",0,0);
  sys3(10,(long)"/tmp/lnk_owners",0,0);sys3(10,(long)"/tmp/lnk_props",0,0);sys3(10,(long)"/tmp/lnk_log",0,0);sys3(10,(long)"/tmp/lnk_other",0,0);
  sys3(83,(long)"/tmp/files/images/j7ekmu/root/dhd.owners",(long)"/tmp/lnk_owners",0);
  sys3(83,(long)"/tmp/files/images/j7ekmu/root/system/lib/libart.so",(long)"/tmp/lnk_other",0);
  sys3(83,(long)"/data/user/0/app.aemu.plus/files/images/j7ekmu/run/zygote.log",(long)"/tmp/lnk_log",0);
  sys3(83,(long)"/dev/__properties__",(long)"/tmp/lnk_props",0);
  bad+=!check("owners table -> framework jar","/tmp/lnk_owners","/system/framework/aemu-owners.jar");
  bad+=!check("other file under root stripped","/tmp/lnk_other","/system/lib/libart.so");
  bad+=!check("host log file -> /dev/null","/tmp/lnk_log","/dev/null");
  bad+=!check("property area -> /dev/null","/tmp/lnk_props","/dev/null");
  sys3(83,(long)"pipe:[12345]",(long)"/tmp/lnk_pipe",0);
  bad+=!check("pipe -> /dev/null","/tmp/lnk_pipe","/dev/null");
  { char b[8]; long r=readlink("/nonexistent/zzz",b,8); w(1,(r==-1&&e==2)?"PASS errno ENOENT\n":"FAIL errno\n"); bad+=!(r==-1&&e==2); }
  { char b[8]; long r=readlinkat(-100,"/tmp/lnk_owners",b,8); /* tiny buffer, must not overflow */ w(1,(r>=0&&r<=8)?"PASS small buf\n":"FAIL small buf\n"); bad+=!(r>=0&&r<=8); }
  // unix socket bound to a host path: getsockname must show the guest path
  { int s=sys3(102,1,(long)(unsigned long[]){1,1,0},0); /* socket(AF_UNIX,SOCK_STREAM) */
    struct sockaddr_un_ a={1,{0}}; const char*p="/tmp/files/images/j7ekmu/root/dev/socket/zygote"; int n=0;while(p[n]){a.path[n]=p[n];n++;}
    unsigned long args[3]={s,(unsigned long)&a,2+n+1}; long r=sys3(102,2,(long)args,0); /* bind */
    struct sockaddr_un_ g; unsigned l=sizeof g; int gr=getsockname(s,&g,&l);
    int ok=(r==0&&gr==0&&eq(g.path,"/dev/socket/zygote",18));
    w(1,ok?"PASS getsockname strips root\n":"FAIL getsockname\n"); bad+=!ok; }

  // --- xattr / seccomp / SO_MARK / timerfd ---
  { char b[64]; long r=getxattr("/tmp","user.default",b,64); w(1,r==0?"PASS user.default present\n":"FAIL user.default\n"); bad+=!(r==0); }
  { char b[64]; long r=getxattr("/tmp","user.serial",b,64); int ok=(r==-1&&e==61); w(1,ok?"PASS user.serial ENODATA\n":"FAIL user.serial\n"); bad+=!ok; }
  // the real call is tried first: a kernel with xattrs answers for itself (any value), one that says ENOSYS gets the fixed label
  sys3(5,(long)"/tmp/aemushim-x86-smoke.tmp",0101 /* O_WRONLY|O_CREAT */,0600);
  { char b[64]; long r=lgetxattr("/tmp/aemushim-x86-smoke.tmp","security.selinux",b,64);
    int ok = r>0 || (r==-1 && e==61 /* ENODATA: real kernel, attribute not set */);
    w(1,ok?"PASS lgetxattr selinux\n":"FAIL lgetxattr selinux\n"); bad+=!ok; }
  { long r=lsetxattr("/tmp/aemushim-x86-smoke.tmp","security.selinux","x",1,0); int ok=(r==0||(r==-1&&e==1 /* EPERM from a real kernel */)); w(1,ok?"PASS lsetxattr returns\n":"FAIL lsetxattr\n"); bad+=!ok; }
  sys3(10,(long)"/tmp/aemushim-x86-smoke.tmp",0,0);
  { int r=prctl(22,2,0,0,0); w(1,r==0?"PASS PR_SET_SECCOMP swallowed\n":"FAIL seccomp\n"); bad+=!(r==0); }
  { int r=prctl(3 /*PR_GET_DUMPABLE*/,0,0,0,0); int ok=(r==0||r==1); w(1,ok?"PASS prctl passthrough\n":"FAIL prctl passthrough\n"); bad+=!ok; }
  { int sk=sys3(102,1,(long)(unsigned long[]){2,1,0},0); /* socket(AF_INET,SOCK_STREAM) */
    int r1=setsockopt(sk,1,36,&bad,4);               /* SO_MARK swallowed */
    int one=1; int r2=setsockopt(sk,1,2 /*SO_REUSEADDR*/,&one,4); /* real call through socketcall */
    int ok=(r1==0&&r2==0); w(1,ok?"PASS setsockopt SO_MARK swallowed, REUSEADDR real\n":"FAIL setsockopt\n"); bad+=!ok; }
  { int fd=timerfd_create(9,0); int ok=fd>=0; w(1,ok?"PASS timerfd_create alarm clock\n":"FAIL timerfd\n"); bad+=!ok; }
  sys3(1,bad?1:0,0,0);
}
