import katex from 'katex'
import renderMathInElement from 'katex/dist/contrib/auto-render.js'
import 'katex/dist/katex.min.css'

// mavon-editor's built-in math plugin reads this browser global.
window.katex = katex

function _ () {
}

const defaultOptions = {
  errorCallback: _,
  throwOnError: false,
  delimiters: [
    {left: '$', right: '$', display: false},
    {left: '$$', right: '$$', display: true},
    {left: '\\[', right: '\\]', display: true},
    {left: '\\(', right: '\\)', display: false}
  ],
  ignoredTags:["script", "noscript", "style", "textarea", "code", "option",],
}

function render (el, binding) {
  let options = {}
  if (binding.value) {
    options = binding.value.options || {}
  }
  Object.assign(options, defaultOptions)
  renderMathInElement(el, options)
}

export default {
  install: function (Vue, options) {
    Vue.directive('katex', {
      bind: render,
      componentUpdated: render
    })
  }
}
