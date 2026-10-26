/** @type {import('tailwindcss').Config} */
// Token 定义与 design-system/MASTER.md §9 严格一致；改色请先改 MASTER.md。
export default {
  content: ['./index.html', './src/**/*.{vue,ts}'],
  theme: {
    extend: {
      colors: {
        obsidian: '#090A0F',
        surface: '#12151E',
        elevated: '#1A1F2E',
        acid: {
          DEFAULT: '#CCFF00',
          bright: '#D9FF3D',
          dim: 'rgba(204,255,0,0.14)',
        },
        signal: {
          orange: '#FF5E36',
          cyan: '#00E5FF',
          coral: '#FF3366',
          violet: '#A78BFA',
        },
        ink: {
          primary: '#F4F6FA',
          secondary: '#9BA3B4',
          muted: '#5C6478',
        },
      },
      borderColor: {
        hairline: 'rgba(255,255,255,0.07)',
        'hairline-strong': 'rgba(255,255,255,0.14)',
      },
      fontFamily: {
        display: ['"Barlow Condensed"', 'system-ui', 'sans-serif'],
        sans: ['Outfit', 'system-ui', 'sans-serif'],
      },
      borderRadius: {
        tile: '16px',
        chip: '12px',
      },
      transitionTimingFunction: {
        kinetic: 'cubic-bezier(.22,.61,.36,1)',
      },
      keyframes: {
        breathe: {
          '0%, 100%': { opacity: '0.55' },
          '50%': { opacity: '1' },
        },
        tileIn: {
          from: { opacity: '0', transform: 'translateY(12px) scale(.97)' },
          to: { opacity: '1', transform: 'translateY(0) scale(1)' },
        },
      },
      animation: {
        breathe: 'breathe 2.4s ease-in-out infinite',
        'tile-in': 'tileIn .42s cubic-bezier(.16,1,.3,1) both',
      },
    },
  },
  plugins: [],
}