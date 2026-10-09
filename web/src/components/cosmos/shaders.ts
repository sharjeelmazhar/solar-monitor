// GLSL for the cosmos backdrop. Everything is procedural (no textures to download), so the whole
// universe costs a few KB on the monitor's flash.

// 3D simplex noise (Ashima Arts / Stefan Gustavson, MIT) + fractal sum.
export const NOISE = /* glsl */ `
vec3 mod289(vec3 x){return x-floor(x*(1./289.))*289.;}
vec4 mod289(vec4 x){return x-floor(x*(1./289.))*289.;}
vec4 permute(vec4 x){return mod289(((x*34.)+1.)*x);}
vec4 taylorInvSqrt(vec4 r){return 1.79284291400159-.85373472095314*r;}
float snoise(vec3 v){
  const vec2 C=vec2(1./6.,1./3.);const vec4 D=vec4(0.,.5,1.,2.);
  vec3 i=floor(v+dot(v,C.yyy));vec3 x0=v-i+dot(i,C.xxx);
  vec3 g=step(x0.yzx,x0.xyz);vec3 l=1.-g;vec3 i1=min(g.xyz,l.zxy);vec3 i2=max(g.xyz,l.zxy);
  vec3 x1=x0-i1+C.xxx;vec3 x2=x0-i2+C.yyy;vec3 x3=x0-D.yyy;
  i=mod289(i);
  vec4 p=permute(permute(permute(i.z+vec4(0.,i1.z,i2.z,1.))+i.y+vec4(0.,i1.y,i2.y,1.))+i.x+vec4(0.,i1.x,i2.x,1.));
  float n_=.142857142857;vec3 ns=n_*D.wyz-D.xzx;
  vec4 j=p-49.*floor(p*ns.z*ns.z);vec4 x_=floor(j*ns.z);vec4 y_=floor(j-7.*x_);
  vec4 x=x_*ns.x+ns.yyyy;vec4 y=y_*ns.x+ns.yyyy;vec4 h=1.-abs(x)-abs(y);
  vec4 b0=vec4(x.xy,y.xy);vec4 b1=vec4(x.zw,y.zw);
  vec4 s0=floor(b0)*2.+1.;vec4 s1=floor(b1)*2.+1.;vec4 sh=-step(h,vec4(0.));
  vec4 a0=b0.xzyw+s0.xzyw*sh.xxyy;vec4 a1=b1.xzyw+s1.xzyw*sh.zzww;
  vec3 p0=vec3(a0.xy,h.x);vec3 p1=vec3(a0.zw,h.y);vec3 p2=vec3(a1.xy,h.z);vec3 p3=vec3(a1.zw,h.w);
  vec4 norm=taylorInvSqrt(vec4(dot(p0,p0),dot(p1,p1),dot(p2,p2),dot(p3,p3)));
  p0*=norm.x;p1*=norm.y;p2*=norm.z;p3*=norm.w;
  vec4 m=max(.6-vec4(dot(x0,x0),dot(x1,x1),dot(x2,x2),dot(x3,x3)),0.);m=m*m;
  return 42.*dot(m*m,vec4(dot(p0,x0),dot(p1,x1),dot(p2,x2),dot(p3,x3)));
}
float fbm(vec3 p){float a=.5,s=0.;for(int i=0;i<5;i++){s+=a*snoise(p);p*=2.03;a*=.5;}return s;}
float fbm3(vec3 p){float a=.5,s=0.;for(int i=0;i<3;i++){s+=a*snoise(p);p*=2.07;a*=.5;}return s;}
`

/** Sphere vertex shader: world normal, object normal (for surface patterns) and view vector. */
export const SPHERE_VERT = /* glsl */ `
varying vec3 vN; varying vec3 vObj; varying vec3 vView; varying vec2 vUv;
void main(){
  vec4 w=modelMatrix*vec4(position,1.);
  vN=normalize(mat3(modelMatrix)*normal); vObj=normalize(position); vView=normalize(cameraPosition-w.xyz); vUv=uv;
  gl_Position=projectionMatrix*viewMatrix*w;
}`

// The sun: boiling granulation, limb darkening; brightness and colour follow solar output (uAct 0..1).
export const SUN_FRAG = /* glsl */ `
uniform float uTime; uniform float uAct; uniform float uNight;
varying vec3 vN; varying vec3 vObj; varying vec3 vView;
${NOISE}
void main(){
  vec3 p=vObj*4.2;
  float n=fbm(p+vec3(0.,uTime*.05,uTime*.035));
  float n2=fbm3(p*3.4-vec3(uTime*.09,0.,uTime*.06));
  float cell=1.-abs(snoise(p*5.+uTime*.12));
  float g=.62+.3*n+.18*n2+.12*cell;
  vec3 deep=mix(vec3(.6,.12,.02),vec3(1.,.42,.06),uAct);
  vec3 c=mix(deep,vec3(1.,.74,.26),smoothstep(.2,.78,g));
  c=mix(c,vec3(1.,.97,.86),smoothstep(.74,1.05,g)*(.45+.55*uAct));
  float mu=max(dot(vN,vView),0.);
  c*=.5+.7*pow(mu,.55);
  c=mix(c,vec3(1.,.6,.2),pow(1.-mu,3.)*.5);
  c*=mix(.55,1.35,uAct)*(1.-.5*uNight);
  gl_FragColor=vec4(c,1.);
}`

// Corona shell around the sun: bright rim that flickers, fades to nothing towards the centre.
export const CORONA_FRAG = /* glsl */ `
uniform float uTime; uniform float uAct; uniform float uLight;
varying vec3 vN; varying vec3 vObj; varying vec3 vView;
${NOISE}
void main(){
  float mu=abs(dot(vN,vView));
  float rim=pow(mu,1.6)*smoothstep(0.,.35,mu);
  float f=.7+.45*fbm3(vObj*4.+vec3(uTime*.15));
  vec3 c=mix(vec3(1.,.38,.06),vec3(1.,.8,.4),rim)*rim*f*(.25+.75*uAct)*.9*(1.-.6*uLight);
  gl_FragColor=vec4(c,1.);
}`

// Earth from real maps (NASA Blue Marble day colour + city lights, public domain), drifting procedural clouds,
// ocean glint and a blue rim. The mesh is turned so the day/night line matches the real time.
export const EARTH_FRAG = /* glsl */ `
uniform float uTime; uniform vec3 uSun; uniform sampler2D uDay; uniform sampler2D uLights;
varying vec3 vN; varying vec3 vObj; varying vec3 vView; varying vec2 vUv;
${NOISE}
void main(){
  vec3 surf=texture2D(uDay,vUv).rgb;
  float lights=texture2D(uLights,vUv).r;
  float water=smoothstep(.06,.0,surf.g-surf.b+.02)*smoothstep(.45,.2,surf.r);
  float cl=smoothstep(.18,.7,fbm3(vObj*3.1+vec3(uTime*.01,0.,uTime*.005)))*.75;
  float dif=dot(vN,uSun);
  float lit=smoothstep(-.1,.3,dif);
  vec3 r=reflect(-uSun,vN);
  float spec=pow(max(dot(r,vView),0.),30.)*water*(1.-cl)*.6;
  vec3 day=mix(surf*1.15,vec3(1.),cl)*lit+spec*vec3(1.,.9,.72);
  vec3 night=vec3(1.,.66,.3)*pow(lights,1.4)*1.8*(1.-cl*.7);
  vec3 col=day+night*(1.-lit)+surf*.035;
  float mu=max(dot(vN,vView),0.);
  col+=vec3(.25,.55,1.)*pow(1.-mu,3.)*(.12+.88*lit);
  gl_FragColor=vec4(col,1.);
}`

// The moon: grey regolith with dark maria and craters, lit by the sun.
export const MOON_FRAG = /* glsl */ `
uniform vec3 uSun;
varying vec3 vN; varying vec3 vObj; varying vec3 vView;
${NOISE}
void main(){
  float m=smoothstep(-.05,.4,fbm3(vObj*1.25+vec3(7.)));
  float d=fbm(vObj*7.)*.5+.5;
  vec3 c=mix(vec3(.8,.79,.77),vec3(.4,.41,.44),m*.8);
  c*=.86+.28*(d-.5);
  float k=snoise(vObj*13.);
  c+=(smoothstep(.62,.72,k)-smoothstep(.72,.9,k)*.7)*.07;
  float lit=smoothstep(-.05,.4,dot(vN,uSun));
  float mu=max(dot(vN,vView),0.);
  gl_FragColor=vec4(c*(.07+.98*lit)+vec3(.55,.62,.8)*pow(1.-mu,2.5)*.25*lit,1.);
}`

// Thin atmosphere halo around the earth (back faces, additive).
export const ATMO_FRAG = /* glsl */ `
uniform vec3 uSun; uniform float uLight;
varying vec3 vN; varying vec3 vObj; varying vec3 vView;
void main(){
  float mu=abs(dot(vN,vView));
  float a=pow(1.-mu,4.)*1.4;
  float lit=smoothstep(-.3,.5,dot(vN,uSun));
  gl_FragColor=vec4(vec3(.3,.6,1.)*a*(.2+.8*lit)*(1.-.9*uLight),1.);
}`

// Sky dome: dark nebula clouds and a faint milky band; in light mode a soft daylight sky instead.
export const SKY_VERT = /* glsl */ `
varying vec3 vDir;
void main(){ vDir=normalize(position); vec4 p=projectionMatrix*modelViewMatrix*vec4(position,1.); gl_Position=p.xyww; }`
export const SKY_FRAG = /* glsl */ `
uniform float uTime; uniform float uLight; uniform float uNight;
varying vec3 vDir;
${NOISE}
void main(){
  vec3 d=normalize(vDir);
  float n=fbm3(d*2.2+vec3(uTime*.004,0.,0.));
  float n2=.5*snoise(d*5.+vec3(0.,uTime*.006,0.))+.25*snoise(d*10.3);
  float band=exp(-pow(dot(d,normalize(vec3(.35,1.,.18))),2.)*9.);
  vec3 base=mix(vec3(.004,.006,.02),vec3(.012,.016,.045),d.y*.5+.5);
  vec3 neb=vec3(.32,.12,.55)*smoothstep(.0,.7,n)*.42+vec3(.05,.3,.5)*smoothstep(.15,.8,n2)*.3;
  vec3 dark=base+neb*(.35+.65*band)+vec3(.1,.11,.2)*band*smoothstep(-.2,.6,n2)*.5;
  dark=mix(dark,dark*vec3(.8,.95,1.4)+vec3(.004,.008,.03),uNight);
  vec3 sky=mix(vec3(.98,.95,.93),vec3(.78,.86,1.),smoothstep(-.3,.8,d.y));
  sky+=vec3(.45,.3,.6)*smoothstep(.2,.9,n)*.08+vec3(.2,.4,.6)*smoothstep(.3,.9,n2)*.06;
  gl_FragColor=vec4(mix(dark,sky,uLight),1.);
}`

// Round soft points used by stars, the galaxy and the energy stream.
export const POINT_FRAG = /* glsl */ `
varying vec3 vCol; varying float vA;
void main(){
  vec2 q=gl_PointCoord-.5; float d=length(q);
  float a=smoothstep(.5,.0,d); a*=a;
  gl_FragColor=vec4(vCol*a*vA,1.);
}`

export const STAR_VERT = /* glsl */ `
attribute float aSize; attribute float aPhase; attribute vec3 color;
uniform float uTime; uniform float uPix; uniform float uLight; uniform float uNight;
varying vec3 vCol; varying float vA;
void main(){
  vec4 mv=modelViewMatrix*vec4(position,1.);
  gl_Position=projectionMatrix*mv;
  gl_PointSize=aSize*uPix;
  vCol=color; vA=(.55+.45*sin(uTime*(.6+aPhase)+aPhase*20.))*(1.-.92*uLight)*(1.+.5*uNight);
}`

// Spiral galaxy: each star orbits faster near the core (differential rotation).
export const GALAXY_VERT = /* glsl */ `
attribute float aR; attribute float aAng; attribute float aSize; attribute vec3 color;
uniform float uTime; uniform float uPix; uniform float uLight;
varying vec3 vCol; varying float vA;
void main(){
  float ang=aAng+uTime*.35/(aR*.35+1.2);
  vec3 p=vec3(cos(ang)*aR,position.y,sin(ang)*aR);
  vec4 mv=modelViewMatrix*vec4(p,1.);
  gl_Position=projectionMatrix*mv;
  gl_PointSize=clamp(aSize*uPix*(220./-mv.z),1.,8.*uPix);
  vCol=color; vA=1.-.75*uLight;
}`

// Energy flowing from the sun to the earth; uFlow (0..1) decides how many particles travel.
export const STREAM_VERT = /* glsl */ `
attribute float aT; attribute float aGate; attribute vec3 aJit;
uniform float uTime; uniform float uFlow; uniform float uPix; uniform vec3 uA; uniform vec3 uB; uniform vec3 uC;
varying vec3 vCol; varying float vA;
void main(){
  float t=fract(aT+uTime*(.05+.12*uFlow));
  vec3 p=mix(mix(uA,uB,t),mix(uB,uC,t),t)+aJit*sin(t*3.1416)*(.9+.4*sin(uTime+aT*30.));
  vec4 mv=modelViewMatrix*vec4(p,1.);
  gl_Position=projectionMatrix*mv;
  gl_PointSize=clamp(uPix*(70./-mv.z),1.,6.*uPix);
  float on=step(aGate,uFlow);
  vA=on*smoothstep(0.,.12,t)*smoothstep(1.,.85,t);
  vCol=mix(vec3(1.,.75,.25),vec3(1.,.95,.7),t);
}`
