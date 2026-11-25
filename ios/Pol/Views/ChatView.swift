import SwiftUI

struct ChatView: View {
    @EnvironmentObject var store: Store
    let threadKey: String
    let addr: String
    let title: String

    @State private var draft = ""
    @State private var sim = 1
    @FocusState private var focused: Bool

    private var messages: [SmsMessage] { store.messages(in: threadKey) }

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(messages) { m in
                            Bubble(message: m).id(m.id)
                        }
                    }
                    .padding(16)
                }
                .scrollDismissesKeyboard(.interactively)
                .onAppear { if let id = messages.last?.id { proxy.scrollTo(id, anchor: .bottom) } }
                .onChange(of: messages.count) {
                    if let id = messages.last?.id { withAnimation(.snappy) { proxy.scrollTo(id, anchor: .bottom) } }
                }
            }
            composer
        }
        .background(Theme.bg)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { store.call(addr, sim: sim) } label: { Image(systemName: "phone.fill") }
                    .accessibilityLabel("تماس با \(title)")
            }
        }
        .onAppear {
            sim = messages.last?.sim ?? store.defaultSim
            store.markRead(threadKey)
        }
        .onChange(of: messages.count) { store.markRead(threadKey) }
    }

    private var composer: some View {
        VStack(spacing: 10) {
            SimPicker(slot: $sim)
            HStack(spacing: 8) {
                TextField("پیامک", text: $draft, axis: .vertical)
                    .lineLimit(1...5)
                    .focused($focused)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 11)
                    .background(Theme.field, in: RoundedRectangle(cornerRadius: 22))
                Button {
                    let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !text.isEmpty else { return }
                    store.sendSms(to: addr, body: text, sim: sim)
                    draft = ""
                } label: {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(Theme.onAccent)
                        .frame(width: 44, height: 44)
                        .background(Theme.accent, in: Circle())
                }
                .accessibilityLabel("ارسال")
                .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 10)
        .padding(.bottom, 8)
        .background(Color(red: 0.055, green: 0.067, blue: 0.082))
    }
}

struct Bubble: View {
    let message: SmsMessage

    var body: some View {
        HStack {
            if !message.incoming { Spacer(minLength: 60) }
            VStack(alignment: .trailing, spacing: 2) {
                Text(message.body)
                    .font(.system(size: 15))
                    .frame(maxWidth: .infinity, alignment: .leading)
                HStack(spacing: 4) {
                    if message.failed == true {
                        Image(systemName: "exclamationmark.circle.fill").font(.system(size: 11))
                        Text("ارسال نشد").font(.system(size: 11, weight: .bold))
                    }
                    if message.pending == true { Image(systemName: "clock").font(.system(size: 10)) }
                    Text(Fa.num(Fa.clock(message.date))).font(.system(size: 11))
                }
                .foregroundStyle(message.incoming ? Theme.muted : Color(red: 0.04, green: 0.29, blue: 0.19))
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .foregroundStyle(message.incoming ? Color.white : Theme.onAccent)
            .background(message.incoming ? Theme.field : (message.failed == true ? Theme.danger : Theme.accent), in: RoundedRectangle(cornerRadius: 18))
            .frame(maxWidth: 290, alignment: message.incoming ? .leading : .trailing)
            if message.incoming { Spacer(minLength: 60) }
        }
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }
}
