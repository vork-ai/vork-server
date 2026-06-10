/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    "./src/main/resources/templates/**/*.html",
    "./src/main/resources/static/**/*.html",
    "./src/main/resources/static/js/**/*.js",
  ],
  theme: {
    extend: {
      colors: {
        vork: {
          DEFAULT: '#fdaa02',
          hover:   '#e89a02',
          dark:    '#cc8601',
        },
      },
    },
  },
  plugins: [],
}
