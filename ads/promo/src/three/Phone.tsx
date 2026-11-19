import React, { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useThree } from "@react-three/fiber";
import { useOffthreadVideoTexture } from "@remotion/three";
import { continueRender, delayRender, staticFile, useCurrentFrame } from "remotion";
import * as THREE from "three";
import { GLTFLoader, type GLTF } from "three/examples/jsm/loaders/GLTFLoader.js";
import { RoomEnvironment } from "three/examples/jsm/environments/RoomEnvironment.js";

// "Off-brand Android Smartphone" by SabasGT (Sketchfab), CC BY 4.0 — colours changed for the ad.
let gltf: Promise<GLTF> | null = null;
const loadPhone = () => (gltf ??= new GLTFLoader().loadAsync(staticFile("models/phone.glb")));

export type Pose = { x: number; y: number; z: number; rx: number; ry: number; rz: number; s: number; glow: number };

/** Meshes are found by material name: GLTFLoader rewrites node names (spaces, dots). */
const byMaterial = (root: THREE.Object3D, name: string) => {
  let found: THREE.Mesh | undefined;
  root.traverse((o) => {
    const m = (o as THREE.Mesh).material as THREE.Material | undefined;
    if (!found && (o as THREE.Mesh).isMesh && m && m.name === name) found = o as THREE.Mesh;
  });
  return found!;
};
const deg = THREE.MathUtils.degToRad;

/** Puts the phone upright (long side on Y), screen towards +Z, camera end at the top, 2 units tall, centred. */
function normalise(root: THREE.Object3D): THREE.Group {
  root.updateMatrixWorld(true);
  const box = new THREE.Box3().setFromObject(root);
  const size = box.getSize(new THREE.Vector3());
  const centre = box.getCenter(new THREE.Vector3());
  const axes = [new THREE.Vector3(1, 0, 0), new THREE.Vector3(0, 1, 0), new THREE.Vector3(0, 0, 1)];
  const ext = [size.x, size.y, size.z];
  const longI = ext.indexOf(Math.max(...ext));
  const thinI = ext.indexOf(Math.min(...ext));
  const screen = byMaterial(root, "Screen_Animada");
  const cam = byMaterial(root, "Flash");
  const sc = new THREE.Box3().setFromObject(screen).getCenter(new THREE.Vector3()).sub(centre);
  const cc = new THREE.Box3().setFromObject(cam).getCenter(new THREE.Vector3()).sub(centre);
  const L = axes[longI].clone().multiplyScalar(Math.sign(cc.getComponent(longI)) || 1);
  const N = axes[thinI].clone().multiplyScalar(Math.sign(sc.getComponent(thinI)) || 1);
  const X = new THREE.Vector3().crossVectors(L, N);
  const basis = new THREE.Matrix4().makeBasis(X, L, N).transpose();
  const inner = new THREE.Group();
  root.position.sub(centre);
  inner.add(root);
  const outer = new THREE.Group();
  inner.applyMatrix4(basis);
  const k = 2 / ext[longI];
  outer.scale.setScalar(k);
  outer.add(inner);
  return outer;
}

function restyle(root: THREE.Object3D) {
  root.traverse((o) => {
    const m = (o as THREE.Mesh).material as THREE.MeshStandardMaterial | undefined;
    if (!m || !(o as THREE.Mesh).isMesh) return;
    const mat = m.clone();
    (o as THREE.Mesh).material = mat;
    if (m.name === "Case_Verde" || m.name === "Boton") {
      mat.color = new THREE.Color("#17171b"); mat.metalness = 0.35; mat.roughness = 0.42;
    } else if (m.name === "BordeMetalico") {
      mat.color = new THREE.Color("#8e8e95"); mat.metalness = 1; mat.roughness = 0.24;
    } else if (m.name === "Flash") {
      mat.emissive = new THREE.Color("#d8d8d8"); mat.emissiveIntensity = 0.5;
    }
    mat.envMapIntensity = 1.1;
  });
}

const Env: React.FC = () => {
  const { gl, scene } = useThree();
  useEffect(() => {
    const pm = new THREE.PMREMGenerator(gl);
    const env = pm.fromScene(new RoomEnvironment(), 0.04).texture;
    scene.environment = env;
    return () => { env.dispose(); pm.dispose(); };
  }, [gl, scene]);
  return null;
};

/** Screen picture: the clip of the scene (frame-exact), or a dark glass when [src] is absent. */
const useScreenMaterial = (tex: THREE.Texture | null, glow: number) =>
  useMemo(() => {
    const mat = new THREE.MeshPhysicalMaterial({ color: "#000000", roughness: 0.05, metalness: 0, clearcoat: 1, clearcoatRoughness: 0.04, toneMapped: false });
    if (tex) {
      tex.colorSpace = THREE.SRGBColorSpace;
      // The glTF screen UVs run along the long side; the 870×1920 clip is cropped to the screen's shape.
      tex.center.set(0.5, 0.5);
      tex.rotation = Math.PI;
      tex.repeat.set(0.955, 1);
      mat.emissiveMap = tex;
      mat.emissive = new THREE.Color("#ffffff");
    }
    mat.emissiveIntensity = glow;
    return mat;
  }, [tex, glow]);

const Model: React.FC<{ tex: THREE.Texture | null; pose: Pose; onDrawn?: () => void }> = ({ tex, pose, onDrawn }) => {
  const [model, setModel] = useState<THREE.Group | null>(null);
  const [handle] = useState(() => delayRender("phone model"));
  useEffect(() => {
    loadPhone().then((g) => {
      const root = g.scene.clone(true);
      restyle(root);
      const n = normalise(root);
      if ((window as any).PHONE_NORMALS) n.traverse((o) => { if ((o as THREE.Mesh).isMesh) (o as THREE.Mesh).material = new THREE.MeshNormalMaterial(); });
      (window as any).PHONE_BOX = new THREE.Box3().setFromObject(n);
      setModel(n);
    });
  }, [handle]);
  const { advance } = useThree();
  // ThreeCanvas draws on demand: draw once the model is in the scene, then let the frame be captured.
  useEffect(() => {
    if (!model) return;
    advance(performance.now());
    continueRender(handle);
  }, [model, advance, handle]);
  const screenMat = useScreenMaterial(tex, pose.glow);
  // Set during render: Remotion draws the canvas right after React commits each frame.
  const screen = useMemo(() => (model ? byMaterial(model, "Screen_Animada") : null), [model]);
  if (screen && screen.material !== screenMat) screen.material = screenMat;
  useLayoutEffect(() => {
    if (!model) return;
    advance(performance.now());
    onDrawn?.();
  });
  if (!model) return null;
  return (
    <group position={[pose.x, pose.y, pose.z]} rotation={[deg(pose.rx), deg(pose.ry), deg(pose.rz)]} scale={pose.s}>
      <primitive object={model} />
    </group>
  );
};

/**
 * The video frame arrives through a state update after its own delayRender is cleared, so this component holds a
 * handle per frame and releases it only once the canvas has been drawn with that frame's texture.
 */
const VideoModel: React.FC<{ src: string; pose: Pose }> = ({ src, pose }) => {
  const frame = useCurrentFrame();
  const tex = useOffthreadVideoTexture({ src: staticFile(src), toneMapped: false });
  const pending = useRef<number | null>(null);
  const seen = useRef<THREE.Texture | null>(null);
  useMemo(() => {
    if (pending.current === null) pending.current = delayRender(`screen frame ${frame}`);
  }, [frame]);
  return <Model tex={tex} pose={pose} onDrawn={() => {
    if (tex && tex !== seen.current && pending.current !== null) {
      seen.current = tex;
      const h = pending.current;
      pending.current = null;
      continueRender(h);
    }
  }} />;
};

export const PhoneScene: React.FC<{ src: string | null; pose: Pose }> = ({ src, pose }) => (
  <>
    <Env />
    <ambientLight intensity={0.15} />
    <directionalLight position={[3, 4, 6]} intensity={1.4} />
    <directionalLight position={[-5, 2, -4]} intensity={2.2} color="#ffffff" />
    <directionalLight position={[4, -1, -5]} intensity={1.2} color="#ffe9ee" />
    {src ? <VideoModel src={src} pose={pose} /> : <Model tex={null} pose={pose} />}
  </>
);
