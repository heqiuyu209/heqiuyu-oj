module.exports = {
  presets: [
    ['@vue/cli-plugin-babel/preset', {
      // Chrome 79 accepts numeric separators, but Webpack 4's parser does not.
      include: ['@babel/plugin-transform-numeric-separator']
    }]
  ]
}
