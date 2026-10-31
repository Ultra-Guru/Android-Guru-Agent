'use client'

// 聊天 Markdown 渲染（GFM 表格 + 代码高亮）
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter'
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism'

function CodeBlock(props: React.ComponentProps<'code'> & { children?: React.ReactNode }) {
  const { className, children } = props
  const match = /language-(\w+)/.exec(className || '')
  if (!match && typeof children === 'string' && !children.includes('\n')) {
    return <code>{children}</code>
  }
  return (
    <SyntaxHighlighter
      language={match?.[1] || 'text'}
      style={oneDark}
      customStyle={{ margin: 0, fontSize: '0.8rem', background: 'transparent', padding: '14px 16px' }}
      codeTagProps={{ style: { fontFamily: 'var(--font-mono)' } }}
    >
      {String(children).replace(/\n$/, '')}
    </SyntaxHighlighter>
  )
}

export function Markdown({ content }: { content: string }) {
  return (
    <div className="chat-md">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          code: CodeBlock as never,
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
}
