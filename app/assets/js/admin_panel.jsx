import "../css/admin_panel.css";

// import "phoenix_html";
// import {Socket} from "phoenix";

// import axios from "axios";
import _ from "lodash";

import React from "react";
import ReactDOM from "react-dom";

class AdminPanel extends React.Component {
    constructor(props) {
        super(props);

        this.state = {};
    }

    componentDidMount() {
        this.props.socket.connect();

        let info_channel = this.props.socket.channel("boards:info", {});

        info_channel
            .join()
            .receive("ok", (board_list) => {
                this.setState({imboards: board_list});
            });

        this.setState({
            info_channel: info_channel
        });
    }

    componentWillUnmount() {
        this.props.socket.disconnect();
    }

    render() {
        let board_elements =
            _.map(
                this.state.imboards,
                (board) => {
                    return (
                        <ImageboardCard
                          imboard={board}
                          board_channel={this.props.socket.channel(`boards:${board.name}`)}
                          key={board.name} />
                    );
                }
            );

        return (
            <div className="card-group">
              {board_elements}
            </div>
        );
    }
}

class ImageboardCard extends React.Component {
    constructor(props) {
        super(props);

        this.state = {imboard: props.imboard};
    }

    componentDidMount() {
        // On channel join we will get some info, save it into the state
        this.props.board_channel
            .join()
            .receive("ok", (info) => {
                this.setState(info);
            });

        // Update the state if something is updated in the database
        this.props.board_channel
            .on("board:info_updated", (update) => {
                this.setState({imboard: update});
            });
        this.props.board_channel
            .on("board:image_count_updated", (updated_num_obj) => {
                this.setState(updated_num_obj);
            });
    }

    componentWillUnmount() {
        this.props.board_channel.leave();
    }

    render() {
        return (
            <div className="card imboard-card">
              <div className="card-header">
                {this.state.imboard.name}
              </div>
              <div className="card-body">
                <h5>Properties</h5>
                <ul className="list-group">
                  <li className="list-group-item">
                    <b>Images indexed</b>: {this.state.indexed_image_count}
                  </li>
                  <li className="list-group-item">
                    <b>Total pages</b>: {this.state.imboard.total_pages}
                  </li>
                </ul>

                <br />

                <h5>Indexing progress</h5>
                <b>({this.state.indexed_image_count} / {this.state.imboard.total_pages * 100 /* FIXME: might not always be 100*/})</b>
                <div className="progress">
                  <div className="progress-bar"
                       role="progressbar"
                       style={{
                           width: ((this.state.indexed_image_count / (this.state.imboard.total_pages * 100)) * 100) + "%"
                       }}>
                  </div>
                </div>

                <br />

                {this.startStopButton()}

              </div>
            </div>
        );
    }

    startStopButton = () => {
        let theAction = !this.state.is_indexing ? "start" : "stop";
        let theText = _.capitalize(theAction);
        let btnClass = "btn " + (!this.state.is_indexing ? "btn-primary" : "btn-danger");

        return (
            <button className={btnClass} onClick={() => this.indexingAction(theAction)}>
              {theText} indexing
            </button>
        );
    }

    indexingAction = (kind) => {
        switch (kind) {
        case "start":
            this.props.board_channel.push("indexing:start")
                .receive("ok", () => { this.setState({is_indexing: true}); });
            break;
        case "stop":
            this.props.board_channel.push("indexing:stop")
                .receive("ok", () => { this.setState({is_indexing: false}); });
            break;
        }
    }
}

ReactDOM.render(
    <AdminPanel socket={new Socket("/admin_panel_socket")}/>,
    document.getElementById("panel-main")
);
