import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { createRenderer, h, nextTick, ref, shallowRef } from 'vue'
import { compileScript, parse } from 'vue/compiler-sfc'

const source = await readFile(new URL('./components/MediaCard.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
const compiled = compileScript(descriptor, {
  id: 'media-card-test',
  inlineTemplate: true,
}).content.replace(/from (['"])vue\1/g, `from ${JSON.stringify(import.meta.resolve('vue'))}`)
const { default: MediaCard } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
)

// A Vue host renderer exercises real component updates without a browser or extra test dependency.
function host() {
  const element = (type, text = '') => ({ type, text, props: {}, children: [], parent: null })
  const remove = (node) => {
    if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
  }
  const insert = (node, parent, anchor = null) => {
    remove(node)
    node.parent = parent
    const index = anchor ? parent.children.indexOf(anchor) : -1
    parent.children.splice(index < 0 ? parent.children.length : index, 0, node)
  }
  const renderer = createRenderer({
    createElement: element,
    createText: (text) => element('text', text),
    createComment: (text) => element('comment', text),
    setText: (node, text) => {
      node.text = text
    },
    setElementText: (node, text) => {
      node.text = text
      node.children = []
    },
    patchProp: (node, key, previous, value) => {
      node.props[key] = value
    },
    parentNode: (node) => node.parent,
    nextSibling: (node) => node.parent?.children[node.parent.children.indexOf(node) + 1] || null,
    setScopeId: () => {},
    insertStaticContent: (content, parent, anchor) => {
      const node = element('static', content)
      insert(node, parent, anchor)
      return [node, node]
    },
    insert,
    remove,
  })
  return { ...renderer, root: element('root') }
}

const descendants = (node) => [node, ...node.children.flatMap(descendants)]

test('media card retains actions, availability, task status and deletion feedback', async () => {
  const item = ref({ id: 7, filename: 'video.mp4', status: 'COMPLETED', uploadTime: 'invalid' })
  const task = ref(null)
  const deleting = ref(false)
  const actions = []
  const { createApp, root } = host()
  const app = createApp({
    render: () =>
      h(MediaCard, {
        item: item.value,
        task: task.value,
        deleting: deleting.value,
        onDelete: (value) => actions.push(['delete', value.id]),
        onDownload: (value) => actions.push(['download', value.id]),
        onTranscribe: (value) => actions.push(['transcribe', value]),
        onAnalyze: (value) => actions.push(['analyze', value.id]),
      }),
  })
  app.mount(root)
  const buttons = () => descendants(root).filter((node) => node.type === 'button')
  for (const button of buttons()) {
    assert.equal(button.props.disabled, false)
    button.props.onClick({ stopPropagation() {} })
  }
  assert.deepEqual(actions, [
    ['delete', 7],
    ['download', 7],
    ['transcribe', 7],
    ['analyze', 7],
  ])
  assert.ok(descendants(root).some((node) => node.text === '--'))
  task.value = { type: 'ai' }
  deleting.value = true
  await nextTick()
  assert.ok(descendants(root).some((node) => node.text === 'ANALYZING'))
  assert.equal(buttons()[0].props.disabled, true)
  item.value.status = 'PROCESSING'
  task.value = null
  await nextTick()
  assert.ok(buttons().every((button) => button.props.disabled))
  assert.ok(descendants(root).some((node) => node.text === 'PROCESSING'))
  app.unmount()
})

test('1,000 unchanged cards skip rendering during ten unrelated parent updates', async () => {
  let renders = 0
  const Card = {
    ...MediaCard,
    setup(props, context) {
      const render = MediaCard.setup(props, context)
      return (...args) => {
        renders += 1
        return render(...args)
      }
    },
  }
  const tick = ref(0)
  const items = shallowRef(
    Array.from({ length: 1_000 }, (_, id) => ({ id, filename: `${id}.mp4`, status: 'COMPLETED' })),
  )
  const { createApp, root } = host()
  const app = createApp({
    render: () =>
      h('main', [
        h('p', String(tick.value)),
        ...items.value.map((item) => h(Card, { key: item.id, item })),
      ]),
  })
  app.mount(root)
  assert.equal(renders, 1_000)
  for (let update = 0; update < 10; update += 1) {
    tick.value += 1
    await nextTick()
  }
  assert.equal(renders, 1_000)
  items.value = [{ ...items.value[0], status: 'FAILED' }, ...items.value.slice(1)]
  await nextTick()
  assert.equal(renders, 1_001)
  app.unmount()
})
