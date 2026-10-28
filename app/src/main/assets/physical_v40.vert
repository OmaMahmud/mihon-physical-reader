attribute vec3 aPos;attribute vec2 aUv;uniform mat4 uProj;uniform vec4 uModel;uniform float uZOffset;uniform float uRtlSign;uniform float uReverseUv;uniform vec4 uShadow;varying vec2 vUv;varying vec3 vWorld;
void main(){
 vec3 p=vec3(aPos.x*uModel.x+uModel.y,aPos.y*uModel.z+uModel.w,aPos.z+uZOffset);

 if(uShadow.w>0.5){p.xy+=max(0.0,p.z)*uShadow.xy;p.z=uShadow.z;}
 p.x*=uRtlSign; vWorld=p;vUv=vec2(mix(aUv.x,1.0-aUv.x,uReverseUv),aUv.y);
 gl_Position=uProj*vec4(p,1.0);
}
