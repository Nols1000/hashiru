import SwiftUI
import SharedLogic

/// Every walk in Apple Health by month, newest first, loading older months on the way down.
struct SessionsView: View {
    let history: HistoryUiState
    let actions: HistoryActions

    private var months: [(month: Date, walks: [PastWalk])] {
        let calendar = Calendar.current
        let grouped = Dictionary(grouping: history.walks) { walk in
            calendar.dateInterval(of: .month, for: walk.start)?.start ?? walk.start
        }
        return grouped.keys.sorted(by: >).map { ($0, grouped[$0] ?? []) }
    }

    var body: some View {
        List {
            if history.loadFailed {
                LoadFailedRow(onRetry: actions.refresh)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            }
            ForEach(months, id: \.month) { month in
                Section {
                    ForEach(month.walks, id: \.id) { walk in
                        NavigationLink(value: walk) {
                            SessionListRow(walk: walk)
                        }
                    }
                } header: {
                    Text(month.month, format: .dateTime.month(.wide).year())
                }
            }
            if history.hasMore {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
                .listRowBackground(Color.clear)
                .onAppear(perform: actions.loadMore)
            }
        }
        .listStyle(.insetGrouped)
        .overlay {
            if history.walks.isEmpty && !history.hasMore && !history.isLoading && !history.loadFailed {
                ContentUnavailableView("No Walks", systemImage: "figure.walk")
            }
        }
        .refreshable { actions.refresh() }
        .navigationTitle("Sessions")
    }
}

private struct SessionListRow: View {
    let walk: PastWalk

    var body: some View {
        HStack(spacing: 12) {
            WalkIcon(walk: walk, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(walk.title)
                    .font(.subheadline)
                Text("\(kilometres(walk.distanceMeters)) KM")
                    .font(.system(.title3, design: .rounded, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(Color.workoutVolt)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(sessionDay(walk.start))
                Text(walk.sourceName)
                    .lineLimit(1)
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Shown above what was loaded before, so a failed read never looks like an empty history.
struct LoadFailedRow: View {
    let onRetry: () -> Void

    var body: some View {
        NoticeRow(
            title: "Couldn’t Load Your Walks", message: "Apple Health didn’t answer.", systemImage: "arrow.clockwise",
            tint: .secondary, action: "Try Again", onAction: onRetry
        )
    }
}

#Preview {
    NavigationStack {
        SessionsView(history: previewHistory(), actions: HistoryActions())
    }
}
#Preview("Load Failed") {
    NavigationStack {
        SessionsView(history: previewHistory(walks: [], loadFailed: true), actions: HistoryActions())
    }
}
