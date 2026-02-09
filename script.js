const photos = [
  "images/photo1.jpg",
  "images/photo2.jpg",
  "images/photo3.jpg"
];

let photoIndex = 0;
const mainPhoto = document.getElementById("mainPhoto");
const message = document.getElementById("dynamicMessage");
const music = document.getElementById("bgMusic");

/* Click → photo changes */
document.getElementById("photoCard").addEventListener("click", () => {
  photoIndex = (photoIndex + 1) % photos.length;
  mainPhoto.src = photos[photoIndex];
  message.innerText = "Every click changes a memory ❤️";
});

/* Reveal button */
document.getElementById("revealBtn").addEventListener("click", () => {
  message.innerHTML = `
    You are my favorite person.<br>
    My safe place.<br>
    My home. ❤️
  `;
  music.play();
  launchConfetti();
});

/* Scroll-based actions */
let lastScrollY = window.scrollY;

window.addEventListener("scroll", () => {
  const current = window.scrollY;

  if (current > lastScrollY + 80) {
    document.body.style.backgroundColor = "#000";
  }

  if (current < lastScrollY - 80) {
    document.body.style.backgroundColor = "#111";
  }

  lastScrollY = current;
});

/* Confetti */
function launchConfetti() {
  for (let i = 0; i < 40; i++) {
    const c = document.createElement("div");
    c.style.position = "fixed";
    c.style.top = "-10px";
    c.style.left = Math.random() * 100 + "vw";
    c.style.width = "8px";
    c.style.height = "8px";
    c.style.background = ["#ff4b6e", "#ffd166", "#4cc9f0"][Math.floor(Math.random()*3)];
    c.style.animation = "fall 3s linear";
    document.body.appendChild(c);
    setTimeout(() => c.remove(), 3000);
  }
}

/* Easter egg */
document.addEventListener("dblclick", () => {
  alert("You just unlocked my heart ❤️\nHappy Birthday!");
});