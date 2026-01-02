<template>
  <!-- 烟花画布 -->
  <canvas ref="fireworksCanvas" class="fireworks-canvas"></canvas>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'

const fireworksCanvas = ref(null)

let animationId = null
let fireworks = []
let particles = []
let onResize = null

const firework_hues = [
  0, // 红
  15, // 橙红
  30, // 金橙
  45, // 金黄
  200, // 天蓝
  220, // 蓝
  260, // 紫
  300 // 粉紫
]

class Firework {
  constructor(x, y) {
    const screenWidth = window.screen.width
    this.x = x
    this.y = y
    this.canvas_y = y
    this.targetY = Math.random() * window.innerHeight * 0.4 + 100
    this.acceleration = screenWidth < 768 ? 0.1 : 0.05 // 加速度
    this.speed = Math.sqrt(2 * this.acceleration * (this.canvas_y - this.targetY)) // 初始速度
    this.hue = firework_hues[Math.floor(Math.random() * firework_hues.length)]
    this.brightness = Math.random() * 20 + 40
    this.alpha = 0.9
  }

  update() {
    this.speed -= this.acceleration
    this.y -= this.speed
  }

  draw(ctx) {
    ctx.beginPath()
    ctx.arc(this.x, this.y, 4, 0, Math.PI * 2)
    ctx.shadowBlur = 10
    ctx.shadowColor = `hsla(${this.hue}, 100%, ${this.brightness}%, ${this.alpha})`
    ctx.fillStyle = `hsla(${this.hue}, 100%, ${this.brightness}%, ${this.alpha})`
    ctx.fill()
    ctx.shadowBlur = 0
  }

  hasReachedTarget() {
    return this.speed <= 0 || this.y <= this.targetY
  }
}

class Particle {
  constructor(x, y, hue) {
    this.x = x
    this.y = y
    this.angle = Math.random() * Math.PI * 2
    this.speed = Math.random() * 3 + 1.5 // 爆炸速度
    this.friction = 0.97 // 摩擦力，让粒子飞得更远
    this.gravity = 0.3 // 重力，让粒子停留更久
    this.hue = hue + Math.random() * 30 - 15
    this.brightness = Math.random() * 25 + 45
    this.alpha = 1 // 提高初始透明度
    this.decay = Math.random() * 0.015 + 0.008 // 消失速度
  }

  update() {
    this.speed *= this.friction
    this.x += Math.cos(this.angle) * this.speed
    this.y += Math.sin(this.angle) * this.speed + this.gravity
    this.alpha -= this.decay
  }

  draw(ctx) {
    ctx.beginPath()
    ctx.arc(this.x, this.y, 3, 0, Math.PI * 2)
    ctx.shadowBlur = 8
    ctx.shadowColor = `hsla(${this.hue}, 90%, ${this.brightness}%, ${this.alpha})`
    ctx.fillStyle = `hsla(${this.hue}, 90%, ${this.brightness}%, ${this.alpha})`
    ctx.fill()
    ctx.shadowBlur = 0
  }
}

const initFireworks = () => {
  if (!fireworksCanvas.value) return

  const canvas = fireworksCanvas.value
  const ctx = canvas.getContext('2d', { alpha: true })
  if (!ctx) return

  const resize = () => {
    canvas.width = window.innerWidth
    canvas.height = window.innerHeight
  }
  onResize = resize
  resize()

  ctx.clearRect(0, 0, canvas.width, canvas.height)
  window.addEventListener('resize', resize)

  const animate = () => {
    ctx.clearRect(0, 0, canvas.width, canvas.height)

    if (Math.random() < 0.02) { //生成概率
      const x = Math.random() * canvas.width
      if(fireworks.length < 3){
        fireworks.push(new Firework(x, canvas.height))
      }
    }

    for (let i = fireworks.length - 1; i >= 0; i--) {
      const firework = fireworks[i]
      firework.update()
      firework.draw(ctx)

      if (firework.hasReachedTarget()) {
        const particleCount = Math.random() * 50 + 60
        for (let j = 0; j < particleCount; j++) {
          particles.push(new Particle(firework.x, firework.y, firework.hue))
        }
        fireworks.splice(i, 1)
      }
    }

    for (let i = particles.length - 1; i >= 0; i--) {
      const particle = particles[i]
      particle.update()
      particle.draw(ctx)
      if (particle.alpha <= 0) particles.splice(i, 1)
    }

    animationId = requestAnimationFrame(animate)
  }

  animate()
}

const stopFireworks = () => {
  if (animationId) cancelAnimationFrame(animationId)
  animationId = null
  fireworks = []
  particles = []

  if (onResize) window.removeEventListener('resize', onResize)
  onResize = null
}

onMounted(() => {
  initFireworks()
})

onUnmounted(() => {
  stopFireworks()
})
</script>

<style scoped>
.fireworks-canvas {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 9999;
  background: transparent;
  opacity: 0.95;
}
</style>
