// Smoke test of the emulated touchpad and keyboard (vinput.h) inside aemushim_x86.c, on any x86-64 Linux that still
// runs 32-bit code (no libc, no NDK). Needs to be root: it binds /dev/aemu_touchpad and /dev/aemu_keyboard.
//   gcc -m32 -O1 -nostdlib -ffreestanding -fno-builtin -fno-stack-protector -fno-pie -no-pie -static \
//       -fno-tree-loop-distribute-patterns -mstackrealign -o /tmp/vinput-x86-smoke tests/vinput-x86-smoke.c && /tmp/vinput-x86-smoke
#include "../aemushim_x86.c"
static int e; int *__errno(void){return &e;}
static long w(int fd,const char*s){int n=0;while(s[n])n++;return sys3(4,fd,(long)s,n);}
static int streq(const char*a,const char*b){while(*a&&*a==*b){a++;b++;}return *a==*b;}
static int bad;
static void ok(int cond,const char*name){w(1,cond?"PASS ":"FAIL ");w(1,name);w(1,"\n");if(!cond)bad++;}
static int listen_at(const char*p){
  sys3(10,(long)p,0,0);
  struct vi_sockaddr a;for(unsigned i=0;i<sizeof a;i++)((char*)&a)[i]=0;a.family=1;
  for(int i=0;p[i];i++)a.path[i]=p[i];
  int s=(int)vi_socket(1);
  unsigned long args[3]={(unsigned long)s,(unsigned long)&a,2+(unsigned long)sizeof(a.path)};
  if(sys3(102,2,(long)args,0)<0)return -1;                 // bind
  unsigned long l[2]={(unsigned long)s,4};sys3(102,4,(long)l,0); // listen
  return s;
}
#define EVIOCGNAME(len) (0x80000000u|((unsigned)(len)<<16)|('E'<<8)|0x06)
#define EVIOCGBIT(ev,len) (0x80000000u|((unsigned)(len)<<16)|('E'<<8)|(0x20+(ev)))
#define EVIOCGABS(a) (0x80000000u|(24u<<16)|('E'<<8)|(0x40+(a)))
#define EVIOCGVERSION 0x80044501u
void _start(void){
  int pad=listen_at("/dev/aemu_touchpad"), kb=listen_at("/dev/aemu_keyboard");
  ok(pad>=0&&kb>=0,"servers bound");
  int fp=open("/dev/input/aemu-touchpad",02000002 /*O_RDWR|O_CLOEXEC*/);
  int fk=open("/dev/input/aemu-keyboard",02000002);
  ok(fp>=0&&fk>=0,"both nodes open to a socket");
  ok(vinput_fd(fp)==VI_PAD&&vinput_fd(fk)==VI_KBD,"descriptors identified by their peer");
  { int d=(int)sys3(41,fp,0,0); /* dup */ ok(d>=0&&vinput_fd(d)==VI_PAD,"dup keeps the identity"); }
  { char n[64];for(int i=0;i<64;i++)n[i]=0;int r=ioctl(fp,EVIOCGNAME(63),n); ok(r>0&&streq(n,"AEmulator Touchpad"),"pad name"); }
  { char n[64];for(int i=0;i<64;i++)n[i]=0;int r=ioctl(fk,EVIOCGNAME(63),n); ok(r>0&&streq(n,"AEmulator Keyboard"),"keyboard name"); }
  { unsigned v=0;int r=ioctl(fp,EVIOCGVERSION,&v); ok(r==0&&v==0x10001,"driver version"); }
  { unsigned char b[8];for(int i=0;i<8;i++)b[i]=0;int r=ioctl(fp,EVIOCGBIT(3,8),b);
    ok(r==8&&(b[6]&(1<<5))&&(b[6]&(1<<6)),"pad declares ABS_MT_POSITION_X/Y"); }
  { int a[6];int r=ioctl(fp,EVIOCGABS(53),a); ok(r==0&&a[2]==VI_PAD_MAX,"pad X range"); }
  { unsigned char b[64];for(int i=0;i<64;i++)b[i]=0;int r=ioctl(fk,EVIOCGBIT(1,64),b);
    ok(r==64&&(b[30/8]&(1<<(30%8))),"keyboard declares KEY_A"); }
  // ordinary paths and descriptors are untouched
  { int f=open("/dev/null",2); ok(f>=0&&vinput_fd(f)==VI_NONE,"other path opens normally");
    int r=ioctl(f,EVIOCGVERSION,(void*)&bad); ok(r==-1&&e==25,"foreign ioctl reaches the kernel (ENOTTY)"); }
  { int r=open("/dev/input/aemu-nonexistent",2); ok(r==-1&&e==2,"missing file keeps ENOENT"); }
  // the app side writes events; the guest reads them through the same descriptor
  { unsigned long args[3]; struct vi_sockaddr pa; unsigned l=sizeof pa; args[0]=(unsigned long)pad;args[1]=(unsigned long)&pa;args[2]=(unsigned long)&l;
    int c=(int)sys3(102,5,(long)args,0); /* accept */ ok(c>=0,"app side accepts the guest");
    long wr=sys3(4,c,(long)"abcd",4); char in[8]; long rd=sys3(3,fp,(long)in,8);
    ok(wr==4&&rd==4&&in[0]=='a',"events flow app -> guest"); }
  sys3(10,(long)"/dev/aemu_touchpad",0,0);sys3(10,(long)"/dev/aemu_keyboard",0,0);
  sys3(1,bad?1:0,0,0);
}
