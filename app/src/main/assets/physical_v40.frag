#extension GL_OES_standard_derivatives : enable
precision highp float;
varying vec2 vUv;varying vec3 vWorld;
uniform sampler2D uTex;uniform sampler2D uPaperGrain;uniform float uShade;uniform float uSpineShadow;
uniform float uShadowPass;uniform float uPageGloss;uniform float uMaterial;uniform float uSpineLocal;
float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
uniform vec2 uCrop;uniform float uTextureFlip;uniform float uGray;uniform float uReaderGray;uniform float uInvert;
void main(){
 if(uShadowPass>0.5){
  float grain=hash(vUv*112.0);
  gl_FragColor=vec4(0.012,0.013,0.018,(0.92+grain*.025)*uShade);return;
 }
 vec2 sampleUv=vec2(uCrop.x+uCrop.y*mix(vUv.x,1.0-vUv.x,uTextureFlip),1.0-vUv.y);
 vec4 ink=texture2D(uTex,sampleUv);
 float mono=dot(ink.rgb,vec3(.2126,.7152,.0722));
 ink.rgb=mix(ink.rgb,vec3(clamp(((mono-.5)*.90+.5)*1.08,0.0,1.0)),uGray);
 ink.rgb=mix(ink.rgb,vec3(dot(ink.rgb,vec3(.2126,.7152,.0722))),uReaderGray);
 ink.rgb=mix(ink.rgb,1.0-ink.rgb,uInvert);
 // Lighting comes from high-left and in front of the reader.
 vec3 lightDir=normalize(vec3(-0.34,0.43,0.84));
 vec3 normal=vec3(0.0,0.0,1.0);
 normal=normalize(cross(dFdx(vWorld),dFdy(vWorld))); if(normal.z<0.0)normal=-normal;
 float ndl=max(dot(normal,lightDir),0.0);
 // Flat pages stay faithful to the original print; only lifted folds
 // get the stronger directional response.
 float lifted=smoothstep(0.018,0.11,vWorld.z);
 float diffuse=mix(1.0,0.79+0.24*ndl,lifted);
 vec3 halfVec=normalize(lightDir+vec3(0.0,0.0,1.0));
 // Broad anisotropic ink-coated sheen responds to the fold normal.
 float spec=pow(max(dot(normal,halfVec),0.0),28.0)*0.19*(0.09+0.91*lifted)*uPageGloss*uMaterial;
 // Physical bitmap texture is sampled in PAGE UV, so the fibers
 // bend with the paper rather than sticking to the screen.
 vec2 paperUV=vUv*vec2(3.2,4.1);
 float fiber=texture2D(uPaperGrain,paperUV).r-0.5;
 // Modulate the ink very slightly as in a Photoshop multiply overlay.
 float paperMultiply=1.0+fiber*0.065*uMaterial;
 // Grain perturbs the specular lobe: ink coating isn't perfectly smooth.
 spec*=clamp(1.0+fiber*0.55,0.55,1.5);
 // Deep but localized book gutter; disabled for portrait scrolling.
 // Flat pages shade by their position in the open book. During a turn,
 // the gutter impression belongs to the PAPER, not the screen. Track
 // distance from the bound edge in page UV so it bends and remains
 // continuous as the sheet moves. Occlusion handles visibility.
 float spineDistance=abs(vWorld.x);
 if(uSpineLocal>0.5)spineDistance=(uSpineLocal<1.5?vUv.x:1.0-vUv.x);
 float gutter=1.0-uSpineShadow*(0.55*exp(-spineDistance*29.0)+0.15*exp(-spineDistance*6.0));
 vec3 result=ink.rgb*paperMultiply*diffuse*gutter*uShade+vec3(spec);
 gl_FragColor=vec4(clamp(result,0.0,1.0),1.0);
}
