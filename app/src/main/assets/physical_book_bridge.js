// Supplies real chapter pages and native controls to the bundled renderer.
let lastReportedPhysicalPage = -1;
function restoreSourcePage(source) {
  orientation = desiredOrientation();
  pages = orientation === 'book' ? bookPages : portraitPages;
  const face = pages.findIndex(page => page.sourceIndex === source && !page.alignmentNotice);
  index = orientation === 'book' ? (source === 0 ? -1 : face % 2 === 1 ? face : face - 1) : Math.max(0, face);
  progress = 0;
  zoom = 1;
  panX = panY = 0;
}
function reportPhysicalPage() {
  if (!pages.length || anim || drag) return;
  const source = pages[Math.max(0, index)]?.sourceIndex;
  if (source !== undefined && source !== lastReportedPhysicalPage) {
    lastReportedPhysicalPage = source;
    MihonPhysical.pageSelected(source);
  }
}
window.seekPhysicalPage = source => {
  if (anim) cancelAnimationFrame(anim);
  anim = null;
  drag = false;
  restoreSourcePage(source);
  layout();
};
async function loadChapterImages() {
  const loaded = [];
  const message = document.querySelector('#msg');
  for (let i = 0; i < images.length; i++) {
    message.firstChild.textContent = `Loading chapter page ${i + 1} / ${images.length}…`;
    const image = await loadImage(images[i]);
    if (!image) {
      message.firstChild.textContent = `Page ${i + 1} could not load. `;
      const retry = document.createElement('button');
      retry.textContent = 'Reload';
      retry.onclick = () => location.reload();
      message.appendChild(retry);
      throw new Error(`Unable to load chapter page ${i + 1}`);
    }
    loaded.push(image);
  }
  message.firstChild.textContent = 'Pinch to zoom · Drag to turn pages ';
  return loaded;
}
document.addEventListener('DOMContentLoaded', () => {
  const header = document.querySelector('header');
  header.querySelector('strong').style.display = 'none';
  for (const [label, action] of [
    ['Menu', () => MihonPhysical.menu()],
    ['Previous chapter', () => MihonPhysical.previousChapter()],
    ['Next chapter', () => MihonPhysical.nextChapter()],
  ]) {
    const button = document.createElement('button');
    button.textContent = label;
    button.onclick = action;
    (label === 'Menu' ? header : document.querySelector('#msg')).appendChild(button);
  }
  const style = document.createElement('style');
  style.textContent = 'header button{padding:8px 5px;font-size:12px}#msg button{padding:5px;margin:3px}main{gap:0}#stage{height:85%}';
  document.head.appendChild(style);
  const canvas = document.querySelector('#gl');
  let menuTouch = null;
  canvas.addEventListener('pointerdown', event => {
    menuTouch = {x: event.clientX, y: event.clientY, id: event.pointerId};
  }, true);
  canvas.addEventListener('pointerup', event => {
    if (!menuTouch || menuTouch.id !== event.pointerId) return;
    const bounds = canvas.getBoundingClientRect();
    const fraction = (event.clientX - bounds.left) / bounds.width;
    const distance = Math.hypot(event.clientX - menuTouch.x, event.clientY - menuTouch.y);
    menuTouch = null;
    if (distance < 8 && fraction > .35 && fraction < .65 && zoom <= 1.001) {
      drag = false;
      progress = 0;
      spreadDrag = null;
      spreadTurn = false;
      touches.delete(event.pointerId);
      event.stopImmediatePropagation();
      MihonPhysical.menu();
    }
  }, true);
  canvas.addEventListener('webglcontextlost', event => {
    event.preventDefault();
    document.querySelector('#msg').firstChild.textContent = 'Graphics context lost. Switch Physical book off and on. ';
  });
});
